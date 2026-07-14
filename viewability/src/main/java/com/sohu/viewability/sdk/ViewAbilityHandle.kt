package com.sohu.viewability.sdk

/**
 * 一次监测任务的生命周期句柄。
 *
 * 句柄允许业务在广告被回收、页面销毁或提前结束时停止监测。close() 是
 * 幂等的，多次调用只会真正释放一次监听器和协程。
 */
class ViewAbilityHandle internal constructor(
    /** 实际执行停止动作的闭包，由监测器创建并持有。 */
    private val stopBlock: () -> Unit,
) : AutoCloseable {

    /** 标识当前句柄是否已经停止，使用 volatile 保证跨线程读取可见。 */
    @Volatile
    private var stopped = false

    /** 停止监测并释放与目标 View 关联的资源。 */
    override fun close() {
        if (!stopped) {
            synchronized(this) {
                if (!stopped) {
                    stopped = true
                    stopBlock()
                }
            }
        }
    }
}
