package com.sohu.viewability

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sohu.viewability.ui.theme.ViewAbilityTheme

/**
 * ViewAbility Demo 的入口页面。
 *
 * 入口页只负责导航，不直接创建被监测 View，也不持有任何曝光 Session。
 * 每一种接入方式都有独立 Activity，实际项目可以按业务页面生命周期照搬。
 */
class MainActivity : ComponentActivity() {

    /** 创建入口页面并展示三个独立场景的导航按钮。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ViewAbilityTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "ViewAbility 有效触点 Demo",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "每个场景都在独立 Activity 中管理监测器、View 生命周期和曝光完成回调。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    /** 打开独立的单个 View 曝光场景。 */
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startActivity(Intent(this@MainActivity, SingleViewActivity::class.java)) },
                    ) {
                        Text("场景一：单个 View")
                    }

                    /** 打开独立的 RecyclerView Item 曝光场景。 */
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startActivity(Intent(this@MainActivity, ListItemActivity::class.java)) },
                    ) {
                        Text("场景二：列表指定 Item")
                    }

                    /** 打开独立的 ScrollView 子 View 曝光场景。 */
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startActivity(Intent(this@MainActivity, ScrollChildActivity::class.java)) },
                    ) {
                        Text("场景三：Scroll 组件中的视图")
                    }

                    /** 打开同一页面同时监测多个 View 的并行检测场景。 */
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startActivity(Intent(this@MainActivity, MultiViewActivity::class.java)) },
                    ) {
                        Text("场景四：多个 View 同时检测")
                    }
                }
            }
        }
    }
}
