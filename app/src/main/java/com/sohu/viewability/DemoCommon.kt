package com.sohu.viewability

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sohu.viewability.sdk.ViewAbilityConfig
import com.sohu.viewability.sdk.ViewAbilityReason
import com.sohu.viewability.sdk.ViewAbilityReport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demo 中统一使用的有效触点规则。
 *
 * 真实广告项目通常会从服务端下发这些参数；Demo 固定使用 50% 可见、
 * 连续 1.5 秒，便于快速观察“达到条件后回调”的完整过程。
 */
internal fun demoConfig(): ViewAbilityConfig = ViewAbilityConfig(
    /** 广告至少需要有一半面积处于有效可见状态。 */
    minimumVisibleRatio = 0.5f,
    /** 广告需要连续满足可见条件 1.5 秒。 */
    minimumContinuousDuration = 1.5.seconds,
    /** 每 100 毫秒采样一次，Demo 可以观察中间状态变化。 */
    samplingInterval = 100.milliseconds,
    /** Demo 需要展示累计时长，因此打开中间采样回调。 */
    notifyIntermediateSamples = true,
)

/**
 * 曝光完成回调的业务状态。
 *
 * SDK 的每一次采样都会返回报告，但只有 `completed=true` 才表示达到有效
 * 触点条件。真实项目应在这个分支中做埋点、计费或上报，不能在普通采样时
 * 重复发送曝光事件。
 */
internal class ExposureCallbackState {
    /** 最近一条 SDK 采样报告，用于界面展示。 */
    var latestReport: ViewAbilityReport? = null

    /** 业务层是否已经收到本次 Session 的有效触点完成回调。 */
    var callbackReceived: Boolean = false

    /**
     * 接收 SDK 回调并转换为业务状态。
     *
     * @param report SDK 返回的当前采样报告。
     * @param onExposureReached 达到曝光条件后的业务回调，只会在 completed=true 时执行。
     */
    fun accept(report: ViewAbilityReport, onExposureReached: (ViewAbilityReport) -> Unit) {
        latestReport = report
        if (report.completed && !callbackReceived) {
            callbackReceived = true
            onExposureReached(report)
        }
    }
}

/**
 * 展示 SDK 最近一次采样以及曝光完成回调状态的通用卡片。
 *
 * @param report 最近一次采样报告；为空表示 View 尚未开始采样。
 * @param callbackReceived 业务层是否已经收到完成回调。
 */
@Composable
internal fun ExposureReportCard(
    report: ViewAbilityReport?,
    callbackReceived: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("曝光回调", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (callbackReceived) {
                    "已达到曝光条件，业务回调已触发（completed=true）"
                } else {
                    "尚未达到曝光条件，等待可见比例和连续时长同时满足"
                },
            )
            Spacer(Modifier.height(10.dp))
            Text("最近一次采样", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(reportText(report))
        }
    }
}

/**
 * 把 SDK 报告转换为 Demo 中可读的中文状态。
 *
 * @param report 最近一次采样报告。
 * @return 当前可见性、遮挡率和连续曝光时长。
 */
internal fun reportText(report: ViewAbilityReport?): String {
    if (report == null) return "等待 View 挂载后开始采样…"

    /** 根据 SDK 判定原因生成说明，便于理解为什么没有累计曝光时间。 */
    val status = when (report.reason) {
        ViewAbilityReason.VALID -> if (report.completed) "已完成有效触点" else "可见时间累计中"
        ViewAbilityReason.TOO_MUCH_COVERED -> "遮挡超过阈值，连续时间已清零"
        ViewAbilityReason.OUTSIDE_WINDOW -> "目标当前位于屏幕外"
        else -> "当前不可计入：${report.reason}"
    }
    return buildString {
        appendLine(status)
        appendLine("可见比例：${(report.visibleRatio * 100).toInt()}%")
        appendLine("遮挡比例：${(report.coveredRatio * 100).toInt()}%")
        append("连续时长：${report.continuousVisibleDuration.inWholeMilliseconds} ms")
    }
}

/**
 * 创建单个 View 场景中的广告内容。
 *
 * @param context 创建 Android View 所需的上下文。
 */
internal class DemoAdView(context: Context) : TextView(context) {
    init {
        gravity = android.view.Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 22f
        text = "广告内容 View\n\nSDK 监测这个 View"
        background = ColorDrawable(Color.rgb(42, 104, 190))
    }
}

/** 将 dp 尺寸转换为当前设备的像素。 */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** 将 dp 尺寸转换为当前 View 所在设备的像素。 */
internal fun View.dp(value: Int): Int = context.dp(value)
