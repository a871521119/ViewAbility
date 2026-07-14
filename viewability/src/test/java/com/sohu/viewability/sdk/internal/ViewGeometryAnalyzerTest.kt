package com.sohu.viewability.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证扫描线面积并算法不会重复计算重叠区域。 */
class ViewGeometryAnalyzerTest {
    /** 两个重叠矩形的交集只能计算一次。 */
    @Test
    fun overlappingOccludersAreCountedOnlyOnce() {
        val area = unionArea(
            listOf(
                OcclusionRect(0, 0, 10, 10),
                OcclusionRect(5, 0, 15, 10),
            ),
        )
        assertEquals(150L, area)
    }

    /** 两个互不相交的遮挡矩形面积应该直接相加。 */
    @Test
    fun disjointOccludersAreAdded() {
        val area = unionArea(
            listOf(
                OcclusionRect(0, 0, 10, 10),
                OcclusionRect(20, 0, 30, 10),
            ),
        )
        assertEquals(200L, area)
    }
}
