package com.sohu.viewability

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sohu.viewability.sdk.ViewAbilityHandle
import com.sohu.viewability.sdk.ViewAbilityMonitor
import com.sohu.viewability.sdk.ViewAbilityReport
import com.sohu.viewability.ui.theme.ViewAbilityTheme

/**
 * 单个 View 曝光检测示例。
 *
 * 这是信息流广告、详情页广告和开屏广告中最基础的接入方式：业务拿到
 * 一个真实广告 View，调用 `monitor.start`，在 `completed=true` 时上报曝光。
 */
class SingleViewActivity : ComponentActivity() {
    /** 当前 Activity 独享的监测器，页面销毁时一起释放。 */
    private lateinit var monitor: ViewAbilityMonitor

    /** 创建页面并开始渲染单个 View 曝光 Demo。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = ViewAbilityMonitor()
        setContent {
            ViewAbilityTheme {
                SingleViewContent(monitor)
            }
        }
    }

    /** 页面销毁时取消采样协程、生命周期监听和 View 挂载监听。 */
    override fun onDestroy() {
        monitor.close()
        super.onDestroy()
    }
}

/** 单个 View 场景的 Compose 内容。 */
@androidx.compose.runtime.Composable
private fun SingleViewContent(monitor: ViewAbilityMonitor) {
    /** 当前遮挡层覆盖广告宽度的比例，0 表示完全不遮挡，1 表示覆盖整个宽度。 */
    var overlayRatio by remember { mutableFloatStateOf(0f) }

    /** 当前真实的广告容器，重新监测时复用它而不是重新创建它。 */
    var surface by remember { mutableStateOf<SingleViewSurface?>(null) }

    /** 当前 Session 的句柄；重置时关闭旧句柄。 */
    var handle by remember { mutableStateOf<ViewAbilityHandle?>(null) }

    /** 业务层对完成事件的状态封装。 */
    val callbackState = remember { ExposureCallbackState() }
    /** 用于触发 Compose 重新绘制的最近报告。 */
    var latestReport by remember { mutableStateOf<ViewAbilityReport?>(null) }
    /** 用于触发 Compose 重新绘制的完成回调状态。 */
    var callbackReceived by remember { mutableStateOf(false) }

    /** 清空当前 Demo 的业务状态，但不改变广告 View 的位置和遮挡大小。 */
    fun resetReportState() {
        callbackState.latestReport = null
        callbackState.callbackReceived = false
        latestReport = null
        callbackReceived = false
    }

    /** 在同一个广告 View 上重新创建 SDK Session，保留手工调整的遮挡状态。 */
    fun restart() {
        val target = surface?.adView ?: return
        handle?.close()
        resetReportState()
        handle = monitor.start(
            view = target,
            impressionId = "demo-single-view",
            config = demoConfig(),
        ) { report ->
            /** 只有 completed=true 才是本次重新监测的曝光完成回调。 */
            callbackState.accept(report) {
                // 真实项目在这里执行曝光埋点、计费或服务端上报。
                callbackReceived = true
            }
            latestReport = callbackState.latestReport
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("单个 View 曝光检测", style = MaterialTheme.typography.headlineSmall)
        Text(
            "达到 50% 可见并连续 1.5 秒后，SDK 会回调 completed=true。",
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            factory = { context ->
                /** 真实广告 View 的父容器，遮挡层作为同层兄弟 View 放在上面。 */
                SingleViewSurface(context).also { newSurface ->
                    surface = newSurface
                    handle = monitor.start(
                        view = newSurface.adView,
                        impressionId = "demo-single-view",
                        config = demoConfig(),
                    ) { report ->
                        /** 所有采样都会进入这里；只有 completed=true 才是曝光完成。 */
                        callbackState.accept(report) {
                            // 真实项目在这里执行曝光埋点、计费或服务端上报。
                            callbackReceived = true
                        }
                        latestReport = callbackState.latestReport
                    }
                }
            },
            update = { newSurface ->
                /** 每次拖动 Slider 都更新同层遮挡 View 的实际宽度。 */
                newSurface.setOverlayWidthRatio(overlayRatio)
            },
        )

        Text("遮挡面积：${(overlayRatio * 100).toInt()}%（手指拖动滑块调整）")
        /** Compose Slider 支持手指拖动，用于连续测试不同遮挡面积阈值。 */
        Slider(
            value = overlayRatio,
            onValueChange = { overlayRatio = it },
            valueRange = 0f..1f,
            steps = 9,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            /** 快速切换到无遮挡状态，便于确认曝光计时是否重新累计。 */
            Button(onClick = { overlayRatio = 0f }) {
                Text("移除遮挡")
            }
            /** 在同一个 View 上创建新的 Session，不会改变遮挡比例或滚动位置。 */
            OutlinedButton(onClick = { restart() }) {
                Text("重新监测")
            }
        }

        ExposureReportCard(latestReport, callbackReceived)
    }

    /** Compose 场景退出时主动关闭句柄，Activity 销毁时由监测器再次兜底。 */
    DisposableEffect(Unit) {
        onDispose { handle?.close() }
    }
}

/** 单个 View 场景的真实 Android 容器。 */
private class SingleViewSurface(context: android.content.Context) : FrameLayout(context) {
    /** SDK 实际监测的广告内容 View。 */
    val adView = DemoAdView(context)

    /** 模拟位于广告上方的不透明同层浮层。 */
    val overlay = View(context)

    init {
        /** 先添加广告内容，再添加遮挡层，确保遮挡层后绘制。 */
        addView(adView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overlay.background = ColorDrawable(Color.argb(255, 25, 25, 25))
        overlay.alpha = 0.92f
        overlay.visibility = GONE
        val overlayParams = LayoutParams(dp(190), LayoutParams.MATCH_PARENT, Gravity.END)
        overlayParams.setMargins(0, dp(16), dp(16), dp(16))
        addView(overlay, overlayParams)
    }

    /**
     * 调整遮挡层宽度，使用宽度比例近似遮挡面积比例。
     *
     * 遮挡层保持与广告同高，因此宽度比例就是主要的面积变化因素；
     * 具体可见比例仍由 SDK 重新读取真实 View 几何结果决定。
     *
     * @param ratio 遮挡宽度比例，范围为 0 到 1。
     */
    fun setOverlayWidthRatio(ratio: Float) {
        val safeRatio = ratio.coerceIn(0f, 1f)
        if (width == 0) {
            post { setOverlayWidthRatio(safeRatio) }
            return
        }
        overlay.visibility = if (safeRatio <= 0f) GONE else VISIBLE
        val params = overlay.layoutParams as LayoutParams
        params.width = (width * safeRatio).toInt()
        overlay.layoutParams = params
    }
}
