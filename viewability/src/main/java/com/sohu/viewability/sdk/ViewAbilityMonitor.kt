package com.sohu.viewability.sdk

import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds
import com.sohu.viewability.sdk.internal.ContinuousVisibilityState
import com.sohu.viewability.sdk.internal.GeometrySample
import com.sohu.viewability.sdk.internal.ViewGeometryAnalyzer

/**
 * 有效触点监测器。
 *
 * 每个广告展示对应一个独立的 Session。Session 使用协程按照配置的
 * 间隔采样，但所有 View 几何信息都在主线程读取，因为 Android 的 View
 * 树会同时被布局和绘制线程状态改变，在后台线程读取容易得到半更新的
 * 尺寸和坐标。
 *
 * 监测器本身不使用静态字段，业务可以在进程级创建一个实例，也可以为
 * 不同业务模块创建独立实例。调用 close() 会取消全部协程、移除生命周期
 * 观察者和 View 挂载监听器，从根源上避免页面销毁后的任务泄漏。
 */
class ViewAbilityMonitor : AutoCloseable {
    /** 管理全部采样协程的父作用域；SupervisorJob 保证单个任务失败不影响其他任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 负责把 View 树状态转换为面积比例和判定原因的几何分析器。 */
    private val analyzer = ViewGeometryAnalyzer()

    /** 以 impressionId 为键的任务表；所有访问都发生在主线程。 */
    private val sessions = LinkedHashMap<String, Session>()

    /** 标识监测器是否已经关闭；关闭后拒绝注册新任务。 */
    private var closed = false

    /**
     * 开始一次一次性有效触点监测。
     *
     * 注册必须在主线程执行，因为方法会给目标 View 添加监听器。回调也
     * 始终在主线程执行。相同 impressionId 再次注册时，旧任务会先关闭，
     * 这样可以处理 RecyclerView、ViewPager 等场景中的 View 复用。
     *
     * @param view 要监测的广告 View。
     * @param impressionId 本次广告展示的唯一标识，不能为空或空白。
     * @param config 可见比例、连续时长和采样间隔等判定配置。
     * @param onReport 每次采样的回调；报告中的 completed=true 表示触点完成。
     * @return 可用于提前停止监测的句柄。
     */
    @MainThread
    fun start(
        /** 被监测的广告 View。 */
        view: View,
        /** 当前广告展示的唯一标识。 */
        impressionId: String,
        /** 当前任务使用的判定规则。 */
        config: ViewAbilityConfig = ViewAbilityConfig(),
        /** 每次采样完成后执行的主线程回调。 */
        onReport: (ViewAbilityReport) -> Unit,
    ): ViewAbilityHandle {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ViewAbilityMonitor.start must be called on the main thread"
        }
        check(!closed) { "ViewAbilityMonitor has already been closed" }
        require(impressionId.isNotBlank()) { "impressionId must not be blank" }
        sessions.remove(impressionId)?.close()
        /** 新建的任务对象，保存本次展示的配置快照和回调。 */
        val session = Session(view, impressionId, config, onReport)
        sessions[impressionId] = session
        session.install()
        return ViewAbilityHandle(
            stopBlock = { stop(impressionId, session) },
        )
    }

    /**
     * 按 impressionId 停止任务。
     *
     * 如果任务已经完成或不存在，方法不会抛异常，适合在多个生命周期
     * 回调中重复调用。
     */
    @MainThread
    fun stop(impressionId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        sessions.remove(impressionId)?.close()
    }

    /** 只停止仍然属于当前广告标识的那一个 Session，防止旧句柄误停新任务。 */
    private fun stop(impressionId: String, expected: Session) {
        if (sessions[impressionId] === expected) {
            sessions.remove(impressionId)
            expected.close()
        }
    }

    /** 关闭监测器并释放全部任务；调用后该实例不可再次 start。 */
    @MainThread
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        sessions.values.toList().forEach(Session::close)
        sessions.clear()
        scope.cancel()
    }

    private inner class Session(
        /** 使用弱引用保存 View，避免任务表反向延长 RecyclerView item 生命周期。 */
        view: View,
        /** 当前 Session 对应的曝光唯一标识。 */
        private val impressionId: String,
        /** 当前 Session 的判定配置快照，任务运行中不会被外部修改。 */
        private val config: ViewAbilityConfig,
        /** 向业务发送采样结果的回调。 */
        private val onReport: (ViewAbilityReport) -> Unit,
    ) {
        /** 目标 View 的弱引用；View 被页面移除后允许垃圾回收。 */
        private val targetReference = WeakReference(view)

        /** 目标 View 所属的生命周期所有者，可能在 View attach 前暂时为空。 */
        private var lifecycleOwner: LifecycleOwner? = null

        /** 注册到生命周期所有者上的观察者实例，释放时必须移除同一个对象。 */
        private var lifecycleObserver: LifecycleEventObserver? = null

        /** 当前 Session 的采样协程；暂停时取消，恢复时重新创建。 */
        private var samplingJob: Job? = null

        /** 把几何有效帧转换为连续时长完成事件的纯 Kotlin 状态机。 */
        private val visibilityState = ContinuousVisibilityState(
            config.minimumContinuousDuration.inWholeMilliseconds,
        )

        /** 标记 Session 是否已经被主动关闭，避免重复移除监听器。 */
        private var closed = false

        /** 监听目标 View 挂载和脱离 Window 的状态变化。 */
        private val attachListener = object : View.OnAttachStateChangeListener {
            /** View 重新挂载后重新绑定生命周期并尝试恢复采样。 */
            override fun onViewAttachedToWindow(attached: View) {
                if (closed) return
                bindLifecycleIfAvailable()
                startSamplingIfAllowed()
            }

            /** View 脱离 Window 后立即暂停并清空连续曝光计时。 */
            override fun onViewDetachedFromWindow(detached: View) {
                stopSampling(resetContinuousState = true)
            }
        }

        /** 安装 View 监听器和生命周期监听器；View 尚未 attach 时延迟到 attach 回调。 */
        fun install() {
            /** 目标 View 的临时强引用，只用于安装监听器。 */
            val target = targetReference.get() ?: return
            target.addOnAttachStateChangeListener(attachListener)
            bindLifecycleIfAvailable()
            if (target.isAttachedToWindow) startSamplingIfAllowed()
        }

        /**
         * 查找并绑定最近的 ViewTreeLifecycleOwner。
         *
         * Compose、Activity 和 Fragment 都会把 LifecycleOwner 放进 ViewTree。
         * 找不到时仍允许对普通 View 进行监测，此时由 attach、焦点和可见性
         * 条件负责兜底。
         */
        private fun bindLifecycleIfAvailable() {
            /** 当前目标 View；如果它已被回收，则无需继续注册监听器。 */
            val target = targetReference.get() ?: return

            /** ViewTree 中最近的生命周期所有者。 */
            val owner = target.findViewTreeLifecycleOwner() ?: return
            if (owner === lifecycleOwner) return
            lifecycleObserver?.let { lifecycleOwner?.lifecycle?.removeObserver(it) }
            lifecycleOwner = owner
            /** 监听页面恢复、暂停和销毁事件的观察者。 */
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> startSamplingIfAllowed()
                    Lifecycle.Event.ON_PAUSE -> stopSampling(resetContinuousState = true)
                    Lifecycle.Event.ON_DESTROY -> finish()
                    else -> Unit
                }
            }
            lifecycleObserver = observer
            owner.lifecycle.addObserver(observer)
        }

        /** 只有在 View 已挂载且页面处于 RESUMED 时才创建采样协程。 */
        private fun startSamplingIfAllowed() {
            /** 目标 View 的当前强引用只在本次检查和协程采样期间使用。 */
            val target = targetReference.get()
            if (closed || target == null || !target.isAttachedToWindow || samplingJob?.isActive == true) return

            /** 页面生命周期状态；没有 LifecycleOwner 的普通 View 使用 null 兜底。 */
            val state = lifecycleOwner?.lifecycle?.currentState
            if (state != null && !state.isAtLeast(Lifecycle.State.RESUMED)) return
            samplingJob = scope.launch {
                while (isActive && !closed && !visibilityState.isCompleted) {
                    sampleOnce(targetReference.get())
                    delay(config.samplingInterval.inWholeMilliseconds.coerceAtLeast(16L))
                }
            }
        }

        /** 停止当前采样协程；可选地同时清零连续可见时间。 */
        private fun stopSampling(resetContinuousState: Boolean) {
            samplingJob?.cancel()
            samplingJob = null
            if (resetContinuousState) visibilityState.reset()
        }

        /**
         * 执行一次完整采样并推进“连续有效时长”状态机。
         *
         * 有效帧会记录起点并累加单调时间；任意无效帧都会清零。达到阈值
         * 时先发送 completed 报告，再停止任务并移除观察者，保证一次展示
         * 最多产生一次完成事件。
         */
        private fun sampleOnce(target: View?) {
            /** 几何分析结果；异常不应中断同一监测器中的其他广告任务。 */
            val geometry = runCatching {
                if (target == null) GeometrySample(ViewAbilityReason.DETACHED, 0f, 1f)
                else analyzer.sample(target, config)
            }
                .getOrElse { GeometrySample(ViewAbilityReason.ANALYSIS_ERROR, 0f, 1f) }
            /** 当前采样的单调时间戳，不能使用 System.currentTimeMillis。 */
            val now = SystemClock.elapsedRealtime()
            /** 用当前几何结果推进连续可见状态机。 */
            val timing = visibilityState.record(
                nowMs = now,
                isValid = geometry.reason == ViewAbilityReason.VALID,
            )

            /** 当前连续有效区间的持续时间；无效帧时为零。 */
            val duration = timing.durationMs

            /** 当前帧是否首次达到有效触点的连续时长阈值。 */
            val hasReachedThreshold = timing.reachedThreshold

            /** 对外发送的不可变报告，不包含 View 引用。 */
            val report = ViewAbilityReport(
                impressionId = impressionId,
                reason = geometry.reason,
                visibleRatio = geometry.visibleRatio,
                coveredRatio = geometry.coveredRatio,
                continuousVisibleDuration = duration.milliseconds,
                sampledAtElapsedRealtimeMs = now,
                completed = hasReachedThreshold,
            )
            // 默认只发送完成事件，避免生产环境每 100ms 触发一次业务层
            // 状态更新；调试页面可通过配置打开中间采样通知。
            if (config.notifyIntermediateSamples || hasReachedThreshold) {
                runCatching { onReport(report) }
            }
            if (hasReachedThreshold) {
                // 一次 Session 只服务一次广告展示。下一次展示必须创建新的
                // Session，这样不会把两次展示的连续时长错误地拼接起来。
                stopSampling(resetContinuousState = false)
                removeObservers()
                sessions.remove(impressionId, this)
            }
        }

        /** 主动关闭 Session，取消协程并移除所有监听器。 */
        fun close() {
            if (closed) return
            closed = true
            stopSampling(resetContinuousState = true)
            removeObservers()
        }

        /**
         * 处理生命周期销毁事件。
         *
         * 与普通 close 的区别在于这里还会从任务表移除自己，避免业务忘记
         * 调用 monitor.stop 时留下已经失效的 Session。
         */
        private fun finish() {
            sessions.remove(impressionId, this)
            close()
        }

        /** 移除生命周期观察者和 View attach 监听器，方法本身可重复调用。 */
        private fun removeObservers() {
            lifecycleObserver?.let { lifecycleOwner?.lifecycle?.removeObserver(it) }
            lifecycleObserver = null
            lifecycleOwner = null
            targetReference.get()?.removeOnAttachStateChangeListener(attachListener)
        }
    }
}
