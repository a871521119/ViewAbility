package com.sohu.viewability.sdk

import kotlin.time.Duration

/**
 * 一次采样结果。
 *
 * 报告只包含不可变的数值和状态，不持有目标 View，业务可以直接把它
 * 写入日志、埋点或网络请求。是否接收未完成的中间报告由配置决定；
 * completed 为 true 时表示本次曝光已经达到有效触点阈值。
 */
data class ViewAbilityReport(
    /** 广告曝光唯一标识，用于去重和服务端对账。 */
    val impressionId: String,

    /** 本次采样的判定原因。 */
    val reason: ViewAbilityReason,

    /** 本次采样得到的可见面积比例，取值范围为 0 到 1。 */
    val visibleRatio: Float,

    /** 本次采样得到的被遮挡或出屏面积比例，取值范围为 0 到 1。 */
    val coveredRatio: Float,

    /** 从最近一次连续有效采样开始累计的时长。 */
    val continuousVisibleDuration: Duration,

    /** 使用 SystemClock.elapsedRealtime() 获取的采样时间，不受系统校时影响。 */
    val sampledAtElapsedRealtimeMs: Long,

    /** 当前采样是否首次达到连续时长阈值。 */
    val completed: Boolean,
)
