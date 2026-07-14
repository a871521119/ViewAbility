package com.sohu.viewability

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
 * 同一页面同时检测多个 View 的示例页面。
 *
 * 这个场景模拟信息流中同时存在多个广告位的情况。每个 View 都使用独立
 * 的 impressionId 和 ViewAbilityHandle，因此一个广告达到有效触点或离开
 * 屏幕后，不会影响其他广告的连续可见时间。
 */
class MultiViewActivity : ComponentActivity() {

    /** 当前页面统一管理多个 View Session 的监测器。 */
    private lateinit var monitor: ViewAbilityMonitor

    /** 创建页面并初始化多 View 曝光检测 Demo。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = ViewAbilityMonitor()
        setContent {
            ViewAbilityTheme {
                MultiViewContent(monitor)
            }
        }
    }

    /** 页面销毁时一次性关闭所有 View 的采样协程和监听器。 */
    override fun onDestroy() {
        monitor.close()
        super.onDestroy()
    }
}

/**
 * 多 View 场景的 Compose 内容。
 *
 * @param monitor 当前页面使用的有效触点监测器。
 */
@Composable
private fun MultiViewContent(monitor: ViewAbilityMonitor) {
    /** 承载多个真实 Android View 的容器引用。 */
    var surface by remember { mutableStateOf<MultiViewSurface?>(null) }

    /** 按 impressionId 保存每个 View 最近一次采样报告。 */
    val reports = remember { mutableStateMapOf<String, ViewAbilityReport>() }

    /** 按 impressionId 保存业务是否收到有效触点完成回调。 */
    val callbackStates = remember { mutableStateMapOf<String, Boolean>() }

    /** 按 impressionId 保存独立的 Session 句柄，便于单独关闭或整体重启。 */
    val handles = remember { mutableStateMapOf<String, ViewAbilityHandle>() }

    /**
     * 关闭当前页面上的所有 Session，并清空界面状态。
     *
     * 清空状态不会移动或重建任何 View，因此重新检测不会改变页面滚动位置。
     */
    fun resetSessions() {
        handles.values.toList().forEach(ViewAbilityHandle::close)
        handles.clear()
        reports.clear()
        callbackStates.clear()
    }

    /**
     * 为容器中的所有 View 创建独立监测任务。
     *
     * 每一个 View 都使用唯一的 impressionId；如果重复使用同一个 ID，SDK
     * 会按照设计关闭旧 Session，这里通过固定的 ID 列表避免意外覆盖。
     */
    fun startAllSessions(targetSurface: MultiViewSurface) {
        resetSessions()
        targetSurface.targets.forEachIndexed { index, target ->
            /** 当前 View 对应的稳定业务展示 ID。真实项目应替换成广告曝光 ID。 */
            val impressionId = "demo-multi-view-$index"
            handles[impressionId] = monitor.start(
                view = target,
                impressionId = impressionId,
                config = demoConfig(),
            ) { report ->
                /** 回调始终在主线程执行，可以安全更新 Compose 状态。 */
                reports[impressionId] = report
                if (report.completed) {
                    /** completed=true 只代表当前 View 完成，不影响其他 View。 */
                    callbackStates[impressionId] = true
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("多个 View 同时曝光检测", style = MaterialTheme.typography.headlineSmall)
        Text(
            "同一个页面同时检测 3 个 View。每个 View 拥有独立的连续计时和完成回调。",
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(330.dp),
            factory = { context ->
                /** 创建一次真实 View 容器，后续重新检测时复用这些 View。 */
                MultiViewSurface(context).also { newSurface ->
                    surface = newSurface
                    startAllSessions(newSurface)
                }
            },
        )

        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** 关闭旧 Session 并在同一批 View 上重新开始检测。 */
            Button(onClick = { surface?.let(::startAllSessions) }) {
                Text("重新检测全部")
            }
            /** 只停止当前页面的监测，不销毁或移动页面上的 View。 */
            OutlinedButton(onClick = { resetSessions() }) {
                Text("停止全部")
            }
        }

        /** 为每个 View 显示独立报告，便于观察完成时间可能不同。 */
        surface?.targets?.forEachIndexed { index, _ ->
            val impressionId = "demo-multi-view-$index"
            MultiViewReportCard(
                title = "View ${index + 1}（$impressionId）",
                report = reports[impressionId],
                callbackReceived = callbackStates[impressionId] == true,
            )
        }
    }

    /** 离开页面时关闭所有句柄，避免 Compose 重组或页面退出后残留任务。 */
    DisposableEffect(Unit) {
        onDispose { resetSessions() }
    }
}

/**
 * 展示单个 View 的独立检测结果。
 *
 * @param title 当前 View 的展示名称。
 * @param report 当前 View 最近一次采样报告。
 * @param callbackReceived 当前 View 是否已经触发完成回调。
 */
@Composable
private fun MultiViewReportCard(
    title: String,
    report: ViewAbilityReport?,
    callbackReceived: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (callbackReceived) {
                    "已触发该 View 的有效触点回调"
                } else {
                    "该 View 尚未达到有效触点条件"
                },
            )
            Text(reportText(report))
        }
    }
}

/**
 * 多 View Demo 的真实 Android 容器。
 *
 * 这里使用 LinearLayout 纵向排列 3 个 View，SDK 监测的是列表中的每个
 * 子 View，而不是外层容器。真实项目可以把 targets 替换为 RecyclerView
 * 当前可见的广告 View、Compose AndroidView 或普通布局子 View。
 */
private class MultiViewSurface(context: Context) : LinearLayout(context) {

    /** 页面中同时参与监测的目标 View 集合。 */
    val targets: List<DemoAdView>

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(Color.rgb(238, 242, 247))

        /** 为每个目标创建独立内容 View，并使用不同颜色方便肉眼区分。 */
        val colors = listOf(
            Color.rgb(42, 104, 190),
            Color.rgb(34, 139, 96),
            Color.rgb(190, 104, 42),
        )
        targets = colors.mapIndexed { index, color ->
            DemoAdView(context).apply {
                text = "同时检测 View ${index + 1}\n\n保持可见 1.5 秒后触发回调"
                background = ColorDrawable(color)
                gravity = Gravity.CENTER
            }
        }
        targets.forEachIndexed { index, target ->
            /** 三个 View 之间保留间距，方便观察单个 View 的可见区域。 */
            val params = LayoutParams(LayoutParams.MATCH_PARENT, dp(88))
            if (index > 0) params.topMargin = dp(8)
            addView(target, params)
        }
    }
}
