package com.sohu.viewability

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sohu.viewability.sdk.ViewAbilityHandle
import com.sohu.viewability.sdk.ViewAbilityMonitor
import com.sohu.viewability.sdk.ViewAbilityReport
import com.sohu.viewability.ui.theme.ViewAbilityTheme

/**
 * RecyclerView 指定 Item 曝光检测示例。
 *
 * 列表广告的关键是监测“目标 ViewHolder 当前持有的真实 View”，并在
 * ViewHolder 被回收或重新绑定时关闭旧 Session，避免把一个 impressionId
 * 错误地留在已经展示其他内容的复用 View 上。
 */
class ListItemActivity : ComponentActivity() {
    /** 当前列表页面独享的监测器。 */
    private lateinit var monitor: ViewAbilityMonitor

    /** 创建列表曝光页面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = ViewAbilityMonitor()
        setContent {
            ViewAbilityTheme {
                ListItemContent(monitor)
            }
        }
    }

    /** 页面销毁时释放列表中可能仍在采样的 Session。 */
    override fun onDestroy() {
        monitor.close()
        super.onDestroy()
    }
}

/** 列表指定 Item 场景的 Compose 内容。 */
@Composable
private fun ListItemContent(monitor: ViewAbilityMonitor) {
    /** 当前被监测的目标 Item View。 */
    var targetItem by remember { mutableStateOf<View?>(null) }
    /** 当前列表容器，用于目标 Item 已被回收后仍能滚动到目标位置。 */
    var recyclerSurface by remember { mutableStateOf<DemoRecyclerSurface?>(null) }
    /** 目标 Item 对应的监测句柄。 */
    var handle by remember { mutableStateOf<ViewAbilityHandle?>(null) }
    /** 最近一次 SDK 采样报告。 */
    var latestReport by remember { mutableStateOf<ViewAbilityReport?>(null) }
    /** 业务层是否已经收到有效触点完成回调。 */
    var callbackReceived by remember { mutableStateOf(false) }

    /** 列表目标 View 的业务回调状态。 */
    val callbackState = remember { ExposureCallbackState() }

    /** 清空当前列表广告 Session 的业务状态，但不重置列表滚动位置。 */
    fun resetReportState() {
        callbackState.latestReport = null
        callbackState.callbackReceived = false
        latestReport = null
        callbackReceived = false
    }

    /** 在指定的真实 Item View 上创建新的 SDK Session。 */
    fun startTargetMonitoring(newTarget: View) {
        handle?.close()
        targetItem = newTarget
        resetReportState()
        handle = monitor.start(
            view = newTarget,
            impressionId = "demo-list-item-2",
            config = demoConfig(),
        ) { report ->
            /** 采样回调持续更新状态，completed=true 时才触发业务曝光回调。 */
            callbackState.accept(report) {
                // 真实项目在这里上报该列表广告的有效曝光事件。
                callbackReceived = true
            }
            latestReport = callbackState.latestReport
        }
    }

    /** 目标 ViewHolder 绑定完成后，开始监测这个真实 Item View。 */
    fun bindTarget(newTarget: View) {
        if (newTarget !== targetItem) startTargetMonitoring(newTarget)
    }

    /** 目标 ViewHolder 被回收后必须停止旧任务，防止复用导致串曝光。 */
    fun recycleTarget(recycledView: View) {
        if (recycledView === targetItem) {
            handle?.close()
            handle = null
            targetItem = null
            callbackState.latestReport = null
            callbackState.callbackReceived = false
            latestReport = null
            callbackReceived = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("列表指定 Item 曝光检测", style = MaterialTheme.typography.headlineSmall)
        Text(
            "目标是 position=2 的真实 Item View，列表滚动会触发挂载、脱离和复用。",
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            factory = { context ->
                /** RecyclerView 只负责承载列表，SDK 不直接监测 RecyclerView。 */
                DemoRecyclerSurface(
                    context = context,
                    onTargetReady = ::bindTarget,
                    onTargetRecycled = ::recycleTarget,
                ).also { recyclerSurface = it }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            /** 手动把目标 Item 滚动到列表中间，模拟列表广告入屏。 */
            Button(onClick = { recyclerSurface?.scrollToTarget() }) {
                Text("滚动到目标")
            }
            /** 把列表滚回顶部，模拟目标广告离开窗口。 */
            OutlinedButton(onClick = { recyclerSurface?.scrollToTop() }) {
                Text("回到顶部")
            }
            /** 只重启当前 Item 的 Session，不重建 RecyclerView，因此不会回到顶部。 */
            OutlinedButton(onClick = {
                targetItem?.let { startTargetMonitoring(it) } ?: run {
                    handle?.close()
                    handle = null
                    resetReportState()
                }
            }) {
                Text("重新监测")
            }
        }

        ExposureReportCard(latestReport, callbackReceived)
    }

    /** Compose 场景离开时释放当前 Item 的句柄。 */
    DisposableEffect(Unit) {
        onDispose { handle?.close() }
    }
}

/** 列表场景的真实 Android 容器。 */
private class DemoRecyclerSurface(
    context: Context,
    /** 目标 Item 绑定完成回调。 */
    onTargetReady: (View) -> Unit,
    /** 目标 Item 被 ViewHolder 回收回调。 */
    onTargetRecycled: (View) -> Unit,
) : FrameLayout(context) {
    /** 列表控件本身。 */
    private val recyclerView = RecyclerView(context)

    init {
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = DemoRecyclerAdapter(
            density = resources.displayMetrics.density,
            onTargetReady = onTargetReady,
            onTargetRecycled = onTargetRecycled,
        )
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, dp(8), 0, dp(8))
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 将目标 position 滚动到可见区域，触发目标 Item 重新进入曝光检测。 */
    fun scrollToTarget() {
        recyclerView.smoothScrollToPosition(TARGET_POSITION)
    }

    /** 将列表回到顶部，验证目标 Item 离开窗口后的计时清零。 */
    fun scrollToTop() {
        recyclerView.smoothScrollToPosition(0)
    }

    private companion object {
        /** Demo 中要监测的目标列表位置。 */
        const val TARGET_POSITION = 2
    }
}

/** 列表 Demo 的 Adapter，负责把目标 position 映射为真实 Item View。 */
private class DemoRecyclerAdapter(
    /** 创建 Item 时使用的屏幕密度。 */
    private val density: Float,
    /** 目标 Item 绑定完成回调。 */
    private val onTargetReady: (View) -> Unit,
    /** 目标 Item 被回收回调。 */
    private val onTargetRecycled: (View) -> Unit,
    /** Demo 指定的目标位置。 */
    private val targetPosition: Int = 2,
) : RecyclerView.Adapter<DemoRecyclerAdapter.ItemHolder>() {
    /** Demo 列表中的固定数据量。 */
    private val itemCountForDemo = 16

    /** 创建一个列表 Item 的 TextView。 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
        /** 新建的列表 Item View。 */
        val itemView = TextView(parent.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.DKGRAY)
            textSize = 17f
            setPadding(dp(16), 0, dp(16), 0)
        }
        return ItemHolder(itemView)
    }

    /** 绑定列表数据；目标位置回调真实 View。 */
    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        val isTarget = position == targetPosition
        holder.textView.text = if (isTarget) {
            "目标 Item（position=$position）\nSDK 监测这个 View"
        } else {
            "普通列表 Item（position=$position）"
        }
        holder.textView.background = ColorDrawable(
            if (isTarget) Color.rgb(46, 125, 50) else Color.rgb(235, 238, 242),
        )
        holder.textView.setTextColor(if (isTarget) Color.WHITE else Color.DKGRAY)
        if (isTarget) onTargetReady(holder.textView)
    }

    /** ViewHolder 被回收时通知业务关闭可能关联的目标 Session。 */
    override fun onViewRecycled(holder: ItemHolder) {
        onTargetRecycled(holder.textView)
        super.onViewRecycled(holder)
    }

    /** 返回 Demo 数据总数。 */
    override fun getItemCount(): Int = itemCountForDemo

    /** 只持有列表 Item TextView 的 ViewHolder。 */
    class ItemHolder(
        /** 当前 ViewHolder 对应的真实 Item View。 */
        val textView: TextView,
    ) : RecyclerView.ViewHolder(textView)

    /** 将 dp 尺寸转换成像素。 */
    private fun dp(value: Int): Int = (value * density).toInt()
}
