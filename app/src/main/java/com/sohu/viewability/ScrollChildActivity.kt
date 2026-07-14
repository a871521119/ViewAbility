package com.sohu.viewability

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
 * ScrollView 子 View 曝光检测示例。
 *
 * 适用于长图文、Feed、WebView 容器外的原生广告位等场景。目标 View 初始
 * 位于滚动区域之外，只有真正进入窗口且满足连续时长后才会触发有效曝光。
 */
class ScrollChildActivity : ComponentActivity() {
    /** 当前滚动页面独享的监测器。 */
    private lateinit var monitor: ViewAbilityMonitor

    /** 创建滚动子 View 曝光页面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = ViewAbilityMonitor()
        setContent {
            ViewAbilityTheme {
                ScrollChildContent(monitor)
            }
        }
    }

    /** 页面销毁时释放监测器和滚动目标的采样任务。 */
    override fun onDestroy() {
        monitor.close()
        super.onDestroy()
    }
}

/** ScrollView 子 View 场景的 Compose 内容。 */
@Composable
private fun ScrollChildContent(monitor: ViewAbilityMonitor) {
    /** 当前 ScrollView 容器，用于控制滚动位置。 */
    var surface by remember { mutableStateOf<DemoScrollSurface?>(null) }
    /** 当前目标子 View 的监测句柄。 */
    var handle by remember { mutableStateOf<ViewAbilityHandle?>(null) }
    /** 最近一次采样报告。 */
    var latestReport by remember { mutableStateOf<ViewAbilityReport?>(null) }
    /** 业务层是否收到曝光完成回调。 */
    var callbackReceived by remember { mutableStateOf(false) }
    /** 统一封装 SDK 回调中的 completed 判断。 */
    val callbackState = remember { ExposureCallbackState() }

    /** 清空当前 Session 的报告状态，但不改变 ScrollView 当前滚动位置。 */
    fun resetReportState() {
        latestReport = null
        callbackReceived = false
        callbackState.latestReport = null
        callbackState.callbackReceived = false
    }

    /** 在同一个目标子 View 上重新创建 Session，保留当前滚动位置。 */
    fun startMonitoring(target: View) {
        handle?.close()
        resetReportState()
        handle = monitor.start(
            view = target,
            impressionId = "demo-scroll-child",
            config = demoConfig(),
        ) { report ->
            /** 只有 completed=true 才代表目标完成一次有效曝光。 */
            callbackState.accept(report) {
                // 真实项目在这里上报滚动内容中广告的有效曝光。
                callbackReceived = true
            }
            latestReport = callbackState.latestReport
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scroll 子 View 曝光检测", style = MaterialTheme.typography.headlineSmall)
        Text(
            "目标在长内容中初始不可见，滚动进入窗口后开始累计连续曝光时长。",
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            factory = { context ->
                /** 创建长内容并拿到真正需要监测的目标子 View。 */
                DemoScrollSurface(context).also { newSurface ->
                    surface = newSurface
                    startMonitoring(newSurface.targetView)
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            /** 滚动到目标 View，模拟用户将广告滚动入屏。 */
            Button(onClick = { surface?.scrollToTarget() }) {
                Text("滚动到目标")
            }
            /** 滚回顶部，目标离开窗口后连续曝光时长会清零。 */
            OutlinedButton(onClick = { surface?.scrollToTop() }) {
                Text("回到顶部")
            }
            /** 只重启当前目标的 Session，不重建 ScrollView，因此不会回到顶部。 */
            OutlinedButton(onClick = {
                surface?.let { startMonitoring(it.targetView) } ?: run {
                    handle?.close()
                    handle = null
                    resetReportState()
                }
            }) {
                Text("重新监测")
            }
        }

        ExposureReportCard(latestReport, callbackReceived)
    }

    /** Compose 场景退出时关闭当前目标 View 的句柄。 */
    DisposableEffect(Unit) {
        onDispose { handle?.close() }
    }
}

/** ScrollView 场景的真实 Android 容器。 */
private class DemoScrollSurface(context: Context) : FrameLayout(context) {
    /** 垂直滚动控件。 */
    private val scrollView = ScrollView(context)
    /** ScrollView 内部的长内容容器。 */
    private val contentLayout = LinearLayout(context)
    /** SDK 实际监测的目标子 View。 */
    lateinit var targetView: View
        private set

    init {
        contentLayout.orientation = LinearLayout.VERTICAL
        contentLayout.setPadding(dp(12), dp(12), dp(12), dp(12))
        scrollView.addView(
            contentLayout,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        for (index in 0 until 14) {
            /** 当前创建的长内容子项。 */
            val item = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                text = "ScrollView 内容 $index"
                setTextColor(Color.DKGRAY)
                background = ColorDrawable(Color.rgb(238, 241, 245))
            }
            contentLayout.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72)).apply {
                bottomMargin = dp(8)
            })

            if (index == TARGET_INDEX) {
                item.text = "目标子 View（index=$index）\nSDK 监测这个 View"
                item.setTextColor(Color.WHITE)
                item.background = ColorDrawable(Color.rgb(123, 31, 162))
                targetView = item
            }
        }
    }

    /** 平滑滚动到目标子 View。 */
    fun scrollToTarget() {
        scrollView.post { scrollView.smoothScrollTo(0, targetView.top) }
    }

    /** 滚回顶部，验证目标离开窗口后的清零行为。 */
    fun scrollToTop() {
        scrollView.smoothScrollTo(0, 0)
    }

    private companion object {
        /** 长内容中交给 SDK 监测的目标子 View 索引。 */
        const val TARGET_INDEX = 8
    }
}
