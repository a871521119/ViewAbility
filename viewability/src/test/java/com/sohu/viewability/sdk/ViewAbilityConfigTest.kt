package com.sohu.viewability.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/** 验证公开配置对象的边界检查和默认广告口径。 */
class ViewAbilityConfigTest {
    /** 可见比例超过 100% 时必须立即拒绝。 */
    @Test
    fun invalidVisibleRatioIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ViewAbilityConfig(minimumVisibleRatio = 1.1f)
        }
    }

    /** 采样间隔大于连续阈值时无法可靠判定，必须拒绝。 */
    @Test
    fun samplingIntervalCannotExceedThreshold() {
        assertThrows(IllegalArgumentException::class.java) {
            ViewAbilityConfig(
                minimumContinuousDuration = 100.milliseconds,
                samplingInterval = 200.milliseconds,
            )
        }
    }

    /** 默认配置保持常见的“可见 50%、连续 2 秒”口径。 */
    @Test
    fun defaultsUseTheAdvertisingViewabilityConvention() {
        val config = ViewAbilityConfig()
        assertEquals(0.5f, config.minimumVisibleRatio)
        assertEquals(2_000L, config.minimumContinuousDuration.inWholeMilliseconds)
    }
}
