package com.sohu.viewability.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证连续有效时长状态机的状态转移和一次性完成语义。 */
class ContinuousVisibilityStateTest {
    /** 无效帧会清空之前已经累计的连续时长。 */
    @Test
    fun invalidFrameResetsContinuousDuration() {
        val state = ContinuousVisibilityState(thresholdMs = 1_000)
        assertEquals(0L, state.record(nowMs = 0, isValid = true).durationMs)
        assertEquals(500L, state.record(nowMs = 500, isValid = true).durationMs)
        assertEquals(0L, state.record(nowMs = 600, isValid = false).durationMs)
        assertEquals(0L, state.record(nowMs = 700, isValid = true).durationMs)
        assertEquals(100L, state.record(nowMs = 800, isValid = true).durationMs)
    }

    /** 达到阈值的帧只产生一次完成事件，后续输入不会重复完成。 */
    @Test
    fun thresholdIsReportedExactlyOnce() {
        val state = ContinuousVisibilityState(thresholdMs = 1_000)
        assertFalse(state.record(nowMs = 0, isValid = true).reachedThreshold)
        assertTrue(state.record(nowMs = 1_000, isValid = true).reachedThreshold)
        assertTrue(state.isCompleted)
        assertFalse(state.record(nowMs = 2_000, isValid = true).reachedThreshold)
    }
}
