# ViewAbility

Android 广告有效可见性（Viewability）SDK。它判断一个广告 View 在窗口中是否达到指定可见比例，
并持续满足指定时长；条件达成后只回调一次，适合用于曝光埋点、计费与归因。

## SDK 简介

ViewAbility 将一次有效曝光建模为可取消、生命周期感知的监测 Session。它读取真实 Android
View 的几何、窗口裁剪、父容器裁剪和已知不透明遮挡，计算可见面积；任一无效采样帧都会重新
开始累计连续可见时长。

## 基本要求

| 项目 | 要求 |
| --- | --- |
| Android minSdk | 26 |
| 编译语言 | Kotlin / Java |
| 推荐版本 | `1.0.0` |

## 集成

在 app 模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation("io.github.a871521119:viewability:1.0.0")
}
```

制品发布在 [Maven Central](https://central.sonatype.com/artifact/io.github.a871521119/viewability/1.0.0)，
使用 `mavenCentral()` 即可解析。

## 快速开始

```kotlin
private val viewability = ViewAbilityMonitor()

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val handle = viewability.start(
        view = adView,
        impressionId = ad.impressionId,
        config = ViewAbilityConfig(
            minimumVisibleRatio = 0.5f,
            minimumContinuousDuration = 2.seconds,
            samplingInterval = 100.milliseconds,
            notifyIntermediateSamples = false,
        ),
    ) { report ->
        if (report.completed) {
            // 只在这里上报一次有效曝光。
            adReporter.reportViewable(report.impressionId)
        }
    }

    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = handle.close()
    })
}

override fun onDestroy() {
    viewability.close()
    super.onDestroy()
}
```

`start` 与回调均运行在主线程。相同 `impressionId` 重复注册时，旧 Session 会先关闭；
达到阈值后 Session 只完成一次并自动停止。页面销毁、列表 Item 回收或广告被替换时，
请调用返回的 `handle.close()`；该调用可重复执行。

## 配置与回调

| 配置 | 说明 |
| --- | --- |
| `minimumVisibleRatio` | 有效曝光所需的最小可见比例，例如 `0.5f` 表示至少可见 50%。 |
| `minimumContinuousDuration` | 连续达到可见比例的最短时长。任意无效帧都会清零。 |
| `samplingInterval` | 采样间隔；应在精度与主线程开销之间取舍。 |
| `notifyIntermediateSamples` | 是否回调过程采样。生产环境建议关闭，只处理完成事件。 |

`onReport` 会收到采样报告，但仅当 `report.completed == true` 时才表示有效曝光已完成。
普通过程报告不应触发计费、埋点或服务端上报：

```kotlin
val handle = monitor.start(
    view = adView,
    impressionId = impressionId,
    config = config,
) { report ->
    if (report.completed) {
        exposureReporter.report(report.impressionId)
    }
}
```

## 工作原理

1. 读取目标 View 的全局矩形、窗口可见矩形、父容器裁剪、有效透明度、窗口焦点和屏幕交互状态。
2. 按 Android 绘制顺序向上遍历父层级，收集目标之后绘制、且具有已知不透明背景的遮挡 View。
3. 使用坐标压缩的扫描线与动态线段树计算遮挡矩形面积并，重叠区域只计算一次。
4. 合并窗口外面积与遮挡面积，计算 `visibleRatio`；满足阈值才产生有效采样帧。
5. 使用 `SystemClock.elapsedRealtime()` 记录连续有效时长，达到阈值后只回调一次并停止 Session。

几何读取和采样都在主线程进行，避免在后台线程读取正在布局或绘制的 View 树。内部使用协程
管理任务，并在 View attach、Lifecycle `RESUMED` / `PAUSED` 与关闭时自动启动、暂停和释放。

## Demo

`app` 模块提供 Kotlin + Jetpack Compose Demo，可验证以下场景：

| 场景 | 验证内容 |
| --- | --- |
| 单个 View | 添加不透明遮挡层，观察可见比例下降。 |
| 列表指定 Item | RecyclerView 绑定完成后监测真实 Item View，而非整个列表。 |
| ScrollView 子 View | 验证出屏/入屏导致连续时长重新累计。 |
| 多个 View | 为多个广告位独立创建 Session，验证回调和关闭逻辑互不影响。 |

四个场景都支持重新监测：旧句柄会关闭，新 Session 直接建立在原有 View 上，
不会重建页面、重置滚动位置或改变当前遮挡状态。单 View 场景的滑块只改变真实遮挡层宽度，
SDK 仍会基于实际几何结果计算曝光结论。

## 工程结构

| 模块 | 说明 |
| --- | --- |
| `viewability` | 独立 Android Library；对外暴露 `ViewAbilityMonitor`、`ViewAbilityConfig`、`ViewAbilityReport` 与 `ViewAbilityHandle`。 |
| `app` | Compose Demo，包含单 View、列表、滚动容器和多 View 示例。 |

## 适用边界

Android 没有向任意 View 提供“用户实际看到的像素”API，因此 SDK 基于 View 树几何做保守估算。
`SurfaceView`、视频硬件层、跨 Window 系统浮层、自定义 `ViewGroup` 绘制顺序，以及 Drawable
内部仅部分像素不透明等场景不能保证像素级准确。对这些特殊渲染层，业务应提供额外可见区域
或进行专项校准。

## 验证

在工程根目录执行：

```bash
./gradlew :viewability:test :app:assembleDebug
```

单元测试覆盖配置校验，以及遮挡矩形重叠/不重叠时面积并不重复计数的行为。

## License

[Apache License 2.0](LICENSE)
