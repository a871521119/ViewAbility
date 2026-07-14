package com.sohu.viewability.sdk.internal

/**
 * 连续可见时长状态机。
 *
 * 几何分析负责回答“这一帧是否有效”，本类只负责回答“有效帧是否已经
 * 连续达到时长阈值”。把时间状态从 Android View 逻辑中拆出来后，采样
 * 间隔、页面暂停、短暂遮挡和一次性完成都可以用纯 JVM 测试覆盖。
 *
 * 状态转移只有两类：有效帧会建立或延续时间区间，无效帧会清空区间；
 * 一旦达到阈值，状态进入完成态，后续帧不会再次产生完成事件。
 */
internal class ContinuousVisibilityState(
    /** 连续有效触点所要求的最短毫秒数。 */
    private val thresholdMs: Long,
) {
    /** 最近一次连续有效区间的起始单调时间戳。 */
    private var startedAtMs: Long? = null

    /** 是否已经产生过完成事件。 */
    var isCompleted: Boolean = false
        private set

    init {
        require(thresholdMs > 0) { "连续有效时长必须大于零" }
    }

    /**
     * 记录一个时间点的有效性并推进状态机。
     *
     * @param nowMs 当前单调时间戳，不能倒退。
     * @param isValid 当前几何采样是否满足可见比例条件。
     * @return 当前连续时长，以及本次是否首次达到阈值。
     */
    fun record(nowMs: Long, isValid: Boolean): TimingResult {
        require(nowMs >= 0) { "时间戳不能为负数" }

        /** 状态完成后忽略后续输入，保证一次展示只有一个完成事件。 */
        if (isCompleted) return TimingResult(0L, false)

        if (!isValid) {
            startedAtMs = null
            return TimingResult(0L, false)
        }

        /** 第一个有效帧成为本次连续可见区间的起点。 */
        if (startedAtMs == null) startedAtMs = nowMs

        /** 当前连续区间的持续时长。 */
        val durationMs = nowMs - requireNotNull(startedAtMs)
        require(durationMs >= 0) { "时间戳不能倒退" }

        /** 只有达到阈值的这一帧才会把状态切换为完成。 */
        val reachedThreshold = durationMs >= thresholdMs
        if (reachedThreshold) isCompleted = true
        return TimingResult(durationMs, reachedThreshold)
    }

    /** 页面暂停、View 脱离 Window 或重新开始一轮展示时清零状态。 */
    fun reset() {
        startedAtMs = null
        isCompleted = false
    }
}

/** 连续可见状态机对一次输入的输出。 */
internal data class TimingResult(
    /** 当前连续有效区间的时长。 */
    val durationMs: Long,

    /** 当前输入是否首次达到完成阈值。 */
    val reachedThreshold: Boolean,
)
