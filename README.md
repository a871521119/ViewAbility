# ViewAbility

一个面向 Android 广告场景的有效触点（Viewability）SDK。它把“广告 View 在窗口内至少可见多少比例，并连续保持多长时间”建模成一次可取消、生命周期感知的一次性监测任务。

## 工程结构

- `viewability`：独立 Android library，可输出 AAR；只暴露 `ViewAbilityMonitor`、`ViewAbilityConfig`、`ViewAbilityReport` 和 `ViewAbilityHandle`。
- `app`：Kotlin + Jetpack Compose Demo。三个场景分别放在 `SingleViewActivity`、`ListItemActivity`、`ScrollChildActivity` 中，互不共享页面状态，被监测对象始终是真实 Android `View`。

## Demo 场景

Demo 页面顶部可以切换以下三种情况：

1. **单个 View**：直接把广告内容 View 传给 SDK，点击“添加遮挡”可验证同层不透明浮层导致的可见比例下降。
2. **列表指定 Item**：RecyclerView Adapter 在目标位置绑定完成后回调真实 Item View，SDK 监测的是该 Item，而不是整个列表。
3. **Scroll 组件中的视图**：监测 ScrollView 长内容中的指定子 View，初始位于屏幕外，点击“滚动到目标”验证出屏/入屏和连续时长重新累计。

三个场景都支持“重新监测”，会关闭旧句柄并在原有 View 上创建新的监测
Session，不重建 Android View，因此不会强制 ScrollView/RecyclerView 回到顶部，
也会保留单个 View 当前手动设置的遮挡面积。

单个 View 场景提供可手指拖动的遮挡比例 Slider（0%～100%）。遮挡层保持与广告
同高，滑块调整的是遮挡宽度比例，SDK 每次采样仍会根据真实 View 几何结果重新
计算可见面积，不是直接使用 Slider 数值作为曝光结论。

## 曝光完成回调

SDK 已经内置曝光完成回调，不需要业务层再额外实现计时器。`start` 的
`onReport` 会接收采样报告；只有 `report.completed == true` 时，才表示本次
Session 已达到“可见比例 + 连续可见时长”的有效触点条件。生产代码应只在这个
分支中执行曝光埋点、计费或服务端上报，普通 `completed == false` 报告只是过程状态。

```kotlin
val handle = monitor.start(
    view = adView,
    impressionId = impressionId,
    config = config,
) { report ->
    // 所有采样都会回调到这里；只有 completed=true 才是一次有效曝光完成事件。
    if (report.completed) {
        exposureReporter.report(report.impressionId)
    }
}
```

同一个 Session 达到阈值后只回调一次并自动停止。下一次广告展示必须创建新的
`start` Session；页面销毁、列表 Item 回收或广告被替换时，应调用返回的 `handle.close()`。

## 接入示例

```kotlin
private val viewability = ViewAbilityMonitor()

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val handle = viewability.start(
        view = view,
        impressionId = ad.impressionId,
        config = ViewAbilityConfig(
            minimumVisibleRatio = 0.5f,
            minimumContinuousDuration = 2.seconds,
            samplingInterval = 100.milliseconds,
            // 生产环境默认关闭中间采样通知，只在完成时回调。
            notifyIntermediateSamples = false,
        ),
    ) { report ->
        if (report.completed) {
            // 仅在这里上报“有效触点”，不要在普通曝光回调中重复上报。
            adReporter.reportViewable(report.impressionId)
        }
    }

    // 在 View/Fragment/Activity 销毁时调用；close() 可重复调用。
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = handle.close()
    })
}

override fun onDestroy() {
    viewability.close()
    super.onDestroy()
}
```

`start` 要在主线程调用，回调也在主线程执行。相同 `impressionId` 重复注册时，旧任务会先被关闭，避免列表复用造成重复上报。

## 核心算法

1. 读取目标 View 的全局矩形、窗口可见矩形、父容器裁剪关系、有效透明度、窗口焦点和屏幕交互状态。
2. 按 Android 子 View 绘制顺序向上遍历父层级，收集后绘制且具有已知不透明背景的遮挡 View。
3. 对遮挡矩形做坐标压缩的扫描线 + 动态线段树面积并，重叠区域只计算一次。
4. 将出窗口面积与遮挡面积合并，得到覆盖率；满足 `visibleRatio >= minimumVisibleRatio` 才产生有效采样帧。
5. 使用 `SystemClock.elapsedRealtime()` 记录连续有效时间。任意无效帧都会清零，达到阈值后只回调一次并停止该 session。

## 相比旧实现的主动优化

这不是对旧代码的逐行搬运，而是重新划分了职责：

- 几何分析、连续时间状态机、任务生命周期分别独立，算法可以单独测试。
- 遮挡遍历同时考虑绘制索引和 `z` 值，覆盖“先添加但 elevation 更高”的浮层。
- 面积并使用动态坐标压缩和线段树，不再依赖固定数组容量。
- 使用弱引用保存目标 View，并在生命周期销毁或任务完成时移除监听器。
- 默认只通知完成事件，调试场景才打开中间采样回调，降低生产环境主线程开销。
- 使用 `SystemClock.elapsedRealtime()`，避免系统时间校正影响连续时长。
- 对配置、重复 ID、重复 close、任务关闭后的再次 start 都做了明确处理。

旧实现仍然有几个无法通过普通 View API 完全解决的边界：`SurfaceView`/视频硬件层、
跨 Window 的系统浮层、自定义 `ViewGroup` 绘制顺序，以及 Drawable 内部只有部分像素
不透明的情况。SDK 对这些场景采取“已知不透明背景才计入遮挡”的保守策略，线上广告
如果使用特殊渲染层，应由业务提供额外的可见区域或做专项校准，不能把 View 树几何
结果当作像素级真值。

几何读取和采样均在主线程，避免在后台线程读取正在布局/绘制的 View 树。任务由协程管理，并在 View attach、Lifecycle RESUMED/PAUSED 和关闭时自动启动/暂停/释放。

## 设计边界

Android 没有对任意 View 提供“用户实际看到的像素”API，因此 SDK 的遮挡判定基于 View 树几何。`SurfaceView`、硬件合成层、跨 Window 的系统浮层、自定义 `ViewGroup` 绘制顺序等场景需要业务侧提供额外的可见区域信息或进行专项验证。

## 验证

在工程根目录执行：

```bash
./gradlew :viewability:test :app:assembleDebug
```

单元测试覆盖配置校验，以及遮挡矩形重叠/不重叠时面积并算法不重复计数的行为。

## 技术文章与图示

完整的架构、算法公式、工程痛点、生命周期设计、Demo 场景和上线建议见：

- [`docs/有效触点框架技术文章.md`](docs/有效触点框架技术文章.md)
- [`docs/images/viewability-architecture.png`](docs/images/viewability-architecture.png)
- [`docs/images/viewability-lifecycle-flow.png`](docs/images/viewability-lifecycle-flow.png)
- [`docs/images/viewability-occlusion-math.png`](docs/images/viewability-occlusion-math.png)
- [`docs/images/viewability-formulas.png`](docs/images/viewability-formulas.png)
