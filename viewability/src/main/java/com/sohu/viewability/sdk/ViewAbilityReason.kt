package com.sohu.viewability.sdk

/** 最近一次采样被接受或拒绝的原因。 */
enum class ViewAbilityReason {
    /** 当前采样满足可见性条件。 */
    VALID,

    /** 目标 View 尚未挂载到 Window。 */
    DETACHED,

    /** 目标 View 的宽或高为零，无法计算面积。 */
    ZERO_SIZE,

    /** 目标 View 或其父级处于不可见状态。 */
    NOT_SHOWN,

    /** 目标 View 或父级的有效透明度低于判定阈值。 */
    TRANSPARENT,

    /** 目标 View 没有可见区域落在当前 Window 中。 */
    OUTSIDE_WINDOW,

    /** 遮挡或出屏面积过大，剩余可见比例低于配置阈值。 */
    TOO_MUCH_COVERED,

    /** 当前设备屏幕处于非交互状态，例如熄屏。 */
    SCREEN_NOT_INTERACTIVE,

    /** 当前 Window 没有焦点，通常表示页面进入后台或被其他 Window 覆盖。 */
    WINDOW_NOT_FOCUSED,

    /** 几何分析过程中发生异常，当前采样按不可见处理。 */
    ANALYSIS_ERROR,
}
