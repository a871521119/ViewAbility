package com.sohu.viewability.sdk.internal

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.sohu.viewability.sdk.ViewAbilityConfig
import com.sohu.viewability.sdk.ViewAbilityReason

/**
 * 一次几何采样的中间结果。
 *
 * 该类型只在 SDK 内部流转，不把 Android View 引用带到外部，便于后续
 * 对几何算法做纯单元测试，也能降低业务层误持有页面对象的风险。
 */
internal data class GeometrySample(
    /** 本次采样最终的判定原因。 */
    val reason: ViewAbilityReason,

    /** 目标 View 在当前 Window 中实际可见的面积比例。 */
    val visibleRatio: Float,

    /** 目标 View 被遮挡或被 Window 裁剪的面积比例。 */
    val coveredRatio: Float,
)

/**
 * 与 Android Rect 解耦的遮挡矩形。
 *
 * 面积并算法使用这个简单数据结构而不是直接使用 Rect，既避免纯 JVM
 * 单元测试依赖 Android 运行时，也避免把 Rect 的可变性带入扫描线算法。
 */
internal data class OcclusionRect(
    /** 矩形左边界，使用屏幕坐标。 */
    val left: Int,

    /** 矩形上边界，使用屏幕坐标。 */
    val top: Int,

    /** 矩形右边界，不包含该边界像素。 */
    val right: Int,

    /** 矩形下边界，不包含该边界像素。 */
    val bottom: Int,
) {
    /** 当前矩形的像素面积；非法或空矩形返回零。 */
    val area: Long
        get() = (right - left).toLong().coerceAtLeast(0) *
            (bottom - top).toLong().coerceAtLeast(0)
}

/**
 * Android View 可见性和遮挡率分析器。
 *
 * Android 没有提供适用于任意 View 的“用户实际看到的像素比例”接口，
 * 因此这里采用一套可解释的几何推断：
 *
 * 1. 读取目标 View 的完整屏幕矩形和当前可见矩形；
 * 2. 根据父容器层级、子 View 绘制顺序和 Z 轴值收集可能遮挡目标的 View；
 * 3. 对遮挡矩形做面积并，避免多个遮挡层重叠时重复扣减；
 * 4. 叠加出 Window 的面积，再与透明度、焦点和亮屏状态一起判定。
 *
 * 该算法的优势是成本可控、结果可解释、不会像逐像素截图一样造成巨大
 * 内存和性能压力；它的边界是无法识别跨 Window 的硬件合成内容，详情在
 * README 中说明，业务可以为特殊 View 提供额外的遮挡信息。
 */
internal class ViewGeometryAnalyzer {

    /**
     * 对目标 View 进行一次完整采样。
     *
     * 方法必须在主线程调用，因为 Android View 的尺寸、位置和父子关系
     * 可能正在由 UI 线程更新。判定顺序从低成本状态开始，尽早返回可以
     * 避免对不可见 View 执行昂贵的层级遍历和面积计算。
     *
     * @param view 要分析的目标 View。
     * @param config 当前任务的可见比例和遮挡策略。
     * @return 不包含 View 引用的几何采样结果。
     */
    fun sample(view: View, config: ViewAbilityConfig): GeometrySample {
        if (!view.isAttachedToWindow) {
            return GeometrySample(ViewAbilityReason.DETACHED, 0f, 1f)
        }
        if (view.width <= 0 || view.height <= 0) {
            return GeometrySample(ViewAbilityReason.ZERO_SIZE, 0f, 1f)
        }
        if (!view.isShown) {
            return GeometrySample(ViewAbilityReason.NOT_SHOWN, 0f, 1f)
        }
        if (effectiveAlpha(view) <= ALPHA_EPSILON) {
            return GeometrySample(ViewAbilityReason.TRANSPARENT, 0f, 1f)
        }
        if (config.requireWindowFocus && !view.hasWindowFocus()) {
            return GeometrySample(ViewAbilityReason.WINDOW_NOT_FOCUSED, 0f, 1f)
        }
        if (config.requireInteractiveScreen && !isScreenInteractive(view.context)) {
            return GeometrySample(ViewAbilityReason.SCREEN_NOT_INTERACTIVE, 0f, 1f)
        }

        /** 目标 View 未被父容器裁剪前的完整屏幕矩形。 */
        val fullRect = view.fullScreenRect()

        /** Android 根据父容器裁剪结果返回的实际可见矩形。 */
        val visibleRect = Rect()
        val hasVisibleRect = view.getGlobalVisibleRect(visibleRect)

        /** 当前 Window 在屏幕坐标系中的边界。 */
        val currentWindowRect = windowRect(view.context)

        /** 同时落在 View 可见区域和 Window 内的矩形。 */
        val clippedVisibleRect = intersect(visibleRect, currentWindowRect)

        /** 目标 View 的完整像素面积。 */
        val fullArea = fullRect.area
        if (!hasVisibleRect || clippedVisibleRect.area == 0L || fullArea == 0L) {
            return GeometrySample(ViewAbilityReason.OUTSIDE_WINDOW, 0f, 1f)
        }

        // 先计算出屏幕边界和父容器裁剪造成的不可见面积。该面积位于
        // Window 外，后面的兄弟 View 遮挡矩形位于 Window 内，两者不会重复。
        val outsideWindowArea = (fullArea - clippedVisibleRect.area).coerceAtLeast(0L)

        /** 当前目标 View 被同层或子层不透明 View 覆盖的矩形集合。 */
        val occluders = collectOccluders(view, fullRect, config.includeOpaqueContainers)

        /** 对所有遮挡矩形求并集，重叠区域只计算一次。 */
        val siblingCoveredArea = unionArea(occluders)

        /** 总覆盖面积不能超过目标 View 本身面积。 */
        val coveredArea = (outsideWindowArea + siblingCoveredArea).coerceIn(0L, fullArea)

        /** 覆盖面积比例，取值范围为 0 到 1。 */
        val coveredRatio = coveredArea.toFloat() / fullArea.toFloat()

        /** 可见面积比例是覆盖比例的补集。 */
        val visibleRatio = 1f - coveredRatio

        /** 只有可见比例达到配置阈值，当前帧才可以参与连续时长累计。 */
        val reason = if (visibleRatio + RATIO_EPSILON >= config.minimumVisibleRatio) {
            ViewAbilityReason.VALID
        } else {
            ViewAbilityReason.TOO_MUCH_COVERED
        }
        return GeometrySample(reason, visibleRatio, coveredRatio)
    }

    /**
     * 收集可能位于目标 View 上方的遮挡矩形。
     *
     * 遍历路径是“目标 View → 父容器 → 更高层父容器”。在每一层中，默认
     * 只有目标之后绘制的兄弟 View 会覆盖目标；另外把 Z 值更高的兄弟也
     * 纳入，修复“较早加入但 elevation 更高”的常见自定义浮层场景。
     */
    private fun collectOccluders(
        target: View,
        targetRect: Rect,
        includeOpaqueContainers: Boolean,
    ): List<OcclusionRect> {
        /** 遮挡矩形输出集合；面积并阶段会继续去重。 */
        val result = ArrayList<OcclusionRect>()

        /** 当前正在向上遍历的子节点。 */
        var child: View = target

        /** 当前子节点的直接父容器。 */
        var parent = child.parent as? ViewGroup
        while (parent != null) {
            /** 目标子节点在当前父容器中的绘制索引。 */
            val targetIndex = parent.indexOfChild(child)

            /** 目标子节点的 Z 值，API 21 起由 elevation 和 translationZ 共同决定。 */
            val targetZ = child.z

            for (index in 0 until parent.childCount) {
                /** 当前待判断的兄弟节点。 */
                val candidate = parent.getChildAt(index)

                /**
                 * 后绘制节点通常覆盖目标；即使索引更小，只要 Z 值更高
                 * 也可能位于目标上方。相同 Z 值的早期节点不会被误判。
                 */
                val isDrawnAbove = index > targetIndex || candidate.z > targetZ + Z_EPSILON
                if (isDrawnAbove) {
                    collectVisibleOpaqueRects(
                        candidate,
                        targetRect,
                        includeOpaqueContainers,
                        result,
                    )
                }
            }

            /** 继续检查更高一层父容器的兄弟节点。 */
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return result
    }

    /**
     * 递归收集一个候选节点内部真正具有不透明背景的区域。
     *
     * 如果候选 ViewGroup 自身不透明，直接使用它的可见矩形即可；继续
     * 遍历子节点只会增加重复矩形。透明容器则必须继续递归，寻找内部
     * 的不透明子 View。
     */
    private fun collectVisibleOpaqueRects(
        candidate: View,
        targetRect: Rect,
        includeOpaqueContainers: Boolean,
        output: MutableList<OcclusionRect>,
    ) {
        if (!candidate.isShown || effectiveAlpha(candidate) <= ALPHA_EPSILON) return

        /** 候选节点考虑父容器裁剪后的可见矩形。 */
        val visible = Rect()
        if (!candidate.getGlobalVisibleRect(visible)) return

        /** 候选节点与目标 View 的实际交集。 */
        val overlap = intersect(visible, targetRect)
        if (overlap.area == 0L) return

        /** 当前节点的背景是否能完整覆盖它的矩形。 */
        val opaque = isOpaque(candidate)
        if (opaque && (candidate !is ViewGroup || includeOpaqueContainers)) {
            output += OcclusionRect(overlap.left, overlap.top, overlap.right, overlap.bottom)
            // 不透明父容器已经覆盖了整个交集，继续遍历子节点不会改变
            // 面积并，只会增加计算量，因此这里直接返回。
            return
        }

        if (candidate is ViewGroup) {
            for (index in 0 until candidate.childCount) {
                /** 当前容器中待递归检查的子节点。 */
                val child = candidate.getChildAt(index)
                collectVisibleOpaqueRects(child, targetRect, includeOpaqueContainers, output)
            }
        }
    }

    /**
     * 判断 View 是否有已知的不透明背景。
     *
     * 只把明确不透明的背景当作遮挡物，避免把透明图片、渐变或自定义
     * 绘制错误地当成整块黑色矩形。硬件合成层和跨 Window 内容无法通过
     * 普通 Drawable 识别，调用方需要在业务层额外处理。
     */
    @Suppress("DEPRECATION")
    private fun isOpaque(view: View): Boolean {
        /** 目标 View 的背景 Drawable；没有背景时不构成完整矩形遮挡。 */
        val background = view.background ?: return false
        if (background.alpha < 255) return false
        if (background is ColorDrawable) {
            return (background.color ushr 24) == 255
        }
        return background.opacity == PixelFormat.OPAQUE
    }

    /**
     * 计算 View 及所有父级 alpha 的乘积。
     *
     * Android 的父容器 alpha 会影响整个子树，仅检查目标 View 自身的
     * alpha 会把“父级淡出动画”误判成可见，因此必须沿父链累乘。
     */
    private fun effectiveAlpha(view: View): Float {
        /** 当前正在向父级移动的 View。 */
        var current: View? = view

        /** 从完全不透明开始累乘每一级 alpha。 */
        var alpha = 1f
        while (current != null) {
            alpha *= current.alpha
            current = current.parent as? View
        }
        return alpha
    }

    /**
     * 读取当前 Window 的屏幕坐标边界。
     *
     * Android 11 及以上使用 WindowMetrics，能够适配分屏和可调整窗口；
     * 低版本使用 DisplayMetrics 兜底。SDK 的最小版本为 26，因此这里不
     * 再保留已经无法触发的旧屏幕 API 分支。
     */
    private fun windowRect(context: Context): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.bounds
                ?.let { return Rect(it) }
        }

        /** 低版本设备上可用的屏幕像素尺寸。 */
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** 判断屏幕是否处于用户可交互状态。 */
    private fun isScreenInteractive(context: Context): Boolean {
        /** 系统电源服务；获取失败时按可交互处理，避免误伤曝光数据。 */
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isInteractive
    }

    /** 获取目标 View 未经父容器裁剪的屏幕矩形。 */
    private fun View.fullScreenRect(): Rect {
        /** 屏幕坐标下的左上角位置。 */
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    /** 返回两个矩形的正交集；没有交集时返回空 Rect。 */
    private fun intersect(first: Rect, second: Rect): Rect {
        /** 交集左边界。 */
        val left = maxOf(first.left, second.left)

        /** 交集上边界。 */
        val top = maxOf(first.top, second.top)

        /** 交集右边界。 */
        val right = minOf(first.right, second.right)

        /** 交集下边界。 */
        val bottom = minOf(first.bottom, second.bottom)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else Rect()
    }

    /** 面积比例计算时允许的浮点误差，避免边界值因精度被错误拒绝。 */
    private companion object {
        /** alpha 小于该值时视为完全透明。 */
        const val ALPHA_EPSILON = 0.001f

        /** 可见比例比较时允许的浮点误差。 */
        const val RATIO_EPSILON = 0.0001f

        /** Z 值比较时允许的浮点误差。 */
        const val Z_EPSILON = 0.0001f
    }
}

/** Android Rect 的像素面积；空矩形和非法矩形统一返回零。 */
private val Rect.area: Long
    get() = width().toLong().coerceAtLeast(0) * height().toLong().coerceAtLeast(0)

/**
 * 使用扫描线和动态线段树计算矩形面积并。
 *
 * 算法把每个矩形拆成左右两条竖边，先压缩所有 Y 坐标，再沿 X 方向
 * 扫描。线段树维护当前 X 区间内被覆盖的 Y 总长度，相邻两条竖边之间
 * 的面积就是“当前覆盖 Y 长度 × X 距离”。重叠矩形因为覆盖计数大于零
 * 只贡献一次，最终得到所有遮挡区域的并集面积。
 *
 * 时间复杂度为 O(n log n)，空间复杂度为 O(n)，并且根据输入动态扩容，
 * 不再存在旧实现固定数组导致的遮挡矩形数量上限。
 */
internal fun unionArea(rectangles: List<OcclusionRect>): Long {
    if (rectangles.isEmpty()) return 0L

    /** 过滤空矩形，避免生成没有宽高的扫描事件。 */
    val validRectangles = rectangles.filter { it.area > 0L }
    if (validRectangles.isEmpty()) return 0L

    /** 经过排序去重后的所有水平边界，用于建立 Y 轴离散坐标。 */
    val yCoordinates = validRectangles
        .flatMap { listOf(it.top, it.bottom) }
        .distinct()
        .sorted()
        .toIntArray()
    if (yCoordinates.size < 2) return 0L

    /** 按 X 坐标排序的左右边事件。 */
    val events = validRectangles.flatMap {
        listOf(
            SweepEvent(it.left, it.top, it.bottom, 1),
            SweepEvent(it.right, it.top, it.bottom, -1),
        )
    }.sortedBy { it.x }

    /** 维护当前扫描带被覆盖的 Y 总长度。 */
    val tree = CoverageTree(yCoordinates)

    /** 已累计的矩形并集面积，使用 Long 避免大 View 面积相乘溢出 Int。 */
    var area = 0L

    /** 上一次处理过的 X 坐标。 */
    var previousX = events.first().x

    /** 当前待处理事件在有序事件数组中的下标。 */
    var cursor = 0
    while (cursor < events.size) {
        /** 当前事件组的 X 坐标。 */
        val currentX = events[cursor].x

        // 先把上一组事件到当前 X 之间的扫描带面积加入答案，
        // 再处理当前 X 上的加边/删边事件。
        area += tree.coveredLength * (currentX - previousX).toLong()
        while (cursor < events.size && events[cursor].x == currentX) {
            /** 当前 X 上待更新线段树的事件。 */
            val event = events[cursor]

            /** 事件上边界对应的离散 Y 下标。 */
            val from = yCoordinates.binarySearch(event.top)

            /** 事件下边界对应的离散 Y 下标。 */
            val to = yCoordinates.binarySearch(event.bottom)
            tree.update(from, to - 1, event.delta)
            cursor++
        }
        previousX = currentX
    }
    return area
}

/** 扫描线的一条竖边事件。 */
private data class SweepEvent(
    /** 当前竖边的 X 坐标。 */
    val x: Int,

    /** 矩形上边界的 Y 坐标。 */
    val top: Int,

    /** 矩形下边界的 Y 坐标。 */
    val bottom: Int,

    /** 加入覆盖计数为 1，移除覆盖计数为 -1。 */
    val delta: Int,
)

/**
 * 维护离散 Y 轴区间覆盖长度的线段树。
 *
 * count 表示节点区间被多少个矩形完整覆盖；当 count 大于零时整个
 * 区间都有效，否则从两个子节点的 covered 长度相加得到结果。
 */
private class CoverageTree(
    /** 排序去重后的 Y 坐标；相邻坐标组成一个可覆盖的基本区间。 */
    private val coordinates: IntArray,
) {
    /** 每个线段树节点的完整覆盖计数。 */
    private val count = IntArray(coordinates.size * 4)

    /** 每个线段树节点当前被覆盖的真实 Y 长度。 */
    private val covered = LongArray(coordinates.size * 4)

    /** 根节点代表整个 Y 轴，暴露当前扫描带的覆盖长度。 */
    val coveredLength: Long
        get() = covered[1]

    /** 对闭区间 [queryLeft, queryRight] 增加或减少覆盖计数。 */
    fun update(queryLeft: Int, queryRight: Int, delta: Int) {
        if (queryLeft > queryRight) return
        update(1, 0, coordinates.size - 2, queryLeft, queryRight, delta)
    }

    /** 递归更新线段树节点，并在返回时重新计算覆盖长度。 */
    private fun update(
        /** 当前节点编号。 */
        node: Int,
        /** 当前节点覆盖区间左下标。 */
        left: Int,
        /** 当前节点覆盖区间右下标。 */
        right: Int,
        /** 待更新区间左下标。 */
        queryLeft: Int,
        /** 待更新区间右下标。 */
        queryRight: Int,
        /** 本次覆盖计数变化量。 */
        delta: Int,
    ) {
        if (queryLeft <= left && right <= queryRight) {
            count[node] += delta
            pull(node, left, right)
            return
        }

        /** 当前节点的中点下标。 */
        val middle = (left + right) ushr 1
        if (queryLeft <= middle) update(node * 2, left, middle, queryLeft, queryRight, delta)
        if (queryRight > middle) update(node * 2 + 1, middle + 1, right, queryLeft, queryRight, delta)
        pull(node, left, right)
    }

    /** 根据覆盖计数和子节点结果重新计算当前节点的覆盖长度。 */
    private fun pull(
        /** 当前节点编号。 */
        node: Int,
        /** 当前节点区间左下标。 */
        left: Int,
        /** 当前节点区间右下标。 */
        right: Int,
    ) {
        if (count[node] > 0) {
            covered[node] = coordinates[right + 1].toLong() - coordinates[left].toLong()
        } else if (left == right) {
            covered[node] = 0L
        } else {
            covered[node] = covered[node * 2] + covered[node * 2 + 1]
        }
    }
}
