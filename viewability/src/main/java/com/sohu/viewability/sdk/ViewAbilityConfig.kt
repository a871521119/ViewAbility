package com.sohu.viewability.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 一次有效触点监测任务的判定配置。
 *
 * 配置对象在创建时立即校验，避免采样协程运行到一半才发现服务端配置
 * 不合法。可见比例使用 0 到 1 的小数表示，例如 0.5 表示广告至少有
 * 50% 的面积可见；连续时长和采样间隔使用 Kotlin 的 Duration，避免
 * 在毫秒、秒之间发生单位误用。
 */
data class ViewAbilityConfig(
    /** 触发有效触点所要求的最小可见面积比例。 */
    val minimumVisibleRatio: Float = 0.5f,

    /** 广告必须连续满足可见条件的最短时长。 */
    val minimumContinuousDuration: Duration = 2.seconds,

    /** 两次可见性采样之间的时间间隔；间隔越小精度越高但开销越大。 */
    val samplingInterval: Duration = 100.milliseconds,

    /** 是否把当前 Window 没有焦点视为不可见。 */
    val requireWindowFocus: Boolean = true,

    /** 是否把熄屏状态视为不可见。 */
    val requireInteractiveScreen: Boolean = true,

    /**
     * 是否把尚未完成的中间采样回调给业务。
     *
     * 生产环境通常只需要完成事件，关闭中间回调可以减少主线程状态更新
     * 和日志开销；可视化调试页面可以开启该选项观察每一次判定变化。
     */
    val notifyIntermediateSamples: Boolean = false,

    /**
     * 是否把带有不透明背景的 ViewGroup 作为完整遮挡矩形。
     *
     * 开启后计算速度更快，也符合不透明容器的真实绘制效果；关闭后会
     * 继续检查容器中的叶子 View，适合容器背景与子 View 实际绘制不一致
     * 的自定义场景。
     */
    val includeOpaqueContainers: Boolean = true,
) {

    /** 校验服务端或业务侧传入的配置，尽早失败而不是静默产生错误数据。 */
    init {
        require(minimumVisibleRatio in 0f..1f) {
            "minimumVisibleRatio must be between 0 and 1"
        }
        require(minimumContinuousDuration.isPositive()) {
            "minimumContinuousDuration must be greater than zero"
        }
        require(samplingInterval.isPositive()) {
            "samplingInterval must be greater than zero"
        }
        require(samplingInterval <= minimumContinuousDuration) {
            "samplingInterval must not be longer than minimumContinuousDuration"
        }
    }
}
