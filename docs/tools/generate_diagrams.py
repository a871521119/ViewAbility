"""生成 ViewAbility 技术文章配套的中文 PNG 图示。"""

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyBboxPatch, Rectangle, FancyArrowPatch


ROOT = Path(__file__).resolve().parents[1]
IMAGE_DIR = ROOT / "images"
# 使用当前 macOS 环境稳定存在的中文字体，避免 PNG 中出现方框字符。
FONT_PATH = "/System/Library/Fonts/Supplemental/Songti.ttc"


def box(ax, x, y, w, h, title, body, color="#EAF2FF"):
    """绘制一个带标题和说明的架构节点。"""
    patch = FancyBboxPatch(
        (x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.02",
        linewidth=1.5, edgecolor="#356AE6", facecolor=color,
    )
    ax.add_patch(patch)
    ax.text(x + w / 2, y + h * 0.68, title, ha="center", va="center",
            fontsize=13, fontweight="bold", color="#17315F")
    ax.text(x + w / 2, y + h * 0.32, body, ha="center", va="center",
            fontsize=9.5, color="#26364F", linespacing=1.5)


def arrow(ax, start, end, label=None, color="#4A5568"):
    """绘制带方向的流程箭头。"""
    ax.add_patch(FancyArrowPatch(start, end, arrowstyle="-|>", mutation_scale=15,
                                 linewidth=1.5, color=color,
                                 connectionstyle="arc3,rad=0.0"))
    if label:
        x = (start[0] + end[0]) / 2
        y = (start[1] + end[1]) / 2 + 0.08
        ax.text(x, y, label, ha="center", va="bottom", fontsize=9, color="#39465C")


def architecture():
    """生成整体分层架构图。"""
    fig, ax = plt.subplots(figsize=(15, 8), dpi=180)
    ax.set_xlim(0, 15)
    ax.set_ylim(0, 8)
    ax.axis("off")
    ax.text(7.5, 7.55, "ViewAbility 有效触点 SDK 分层架构", ha="center",
            fontsize=20, fontweight="bold", color="#14213D")

    box(ax, 0.4, 4.9, 3.1, 1.55, "业务 Demo / 宿主页面", "SingleViewActivity\nListItemActivity\nScrollChildActivity", "#FFF4D6")
    box(ax, 4.25, 4.9, 3.1, 1.55, "公开 SDK API", "ViewAbilityMonitor\nViewAbilityConfig\nViewAbilityHandle\nViewAbilityReport", "#E7F7EE")
    box(ax, 8.1, 4.9, 3.1, 1.55, "Session 生命周期", "View attach / detach\nLifecycle RESUMED / PAUSED\n协程采样与一次性完成", "#EDE7FF")
    box(ax, 11.95, 4.9, 2.65, 1.55, "业务回调", "completed = true\n曝光埋点 / 计费\n服务端上报", "#FFE7E7")

    box(ax, 1.1, 1.65, 3.5, 1.55, "几何分析器", "全局矩形 / Window 裁剪\n父子层级 / z 顺序\n透明度 / 焦点 / 亮屏", "#EAF2FF")
    box(ax, 5.85, 1.65, 3.5, 1.55, "面积并算法", "坐标压缩\n扫描线事件\n动态线段树覆盖长度", "#EAF2FF")
    box(ax, 10.6, 1.65, 3.2, 1.55, "连续时长状态机", "有效帧建立区间\n无效帧清零\n达到阈值只完成一次", "#EAF2FF")

    arrow(ax, (3.5, 5.68), (4.25, 5.68), "调用 start")
    arrow(ax, (7.35, 5.68), (8.1, 5.68), "创建 Session")
    arrow(ax, (11.2, 5.68), (11.95, 5.68), "ViewAbilityReport")
    arrow(ax, (5.8, 4.9), (3.2, 3.2), "采样")
    arrow(ax, (4.6, 2.42), (5.85, 2.42), "遮挡矩形")
    arrow(ax, (9.35, 2.42), (10.6, 2.42), "visibleRatio")
    arrow(ax, (12.2, 3.2), (9.5, 4.9), "完成事件")
    ax.text(7.5, 0.55, "核心原则：几何分析、时间状态、生命周期和业务上报相互解耦",
            ha="center", fontsize=12, color="#42526B")
    fig.savefig(IMAGE_DIR / "viewability-architecture.png", bbox_inches="tight")
    plt.close(fig)


def lifecycle():
    """生成一次采样和生命周期流程图。"""
    fig, ax = plt.subplots(figsize=(15, 6.5), dpi=180)
    ax.set_xlim(0, 15)
    ax.set_ylim(0, 7)
    ax.axis("off")
    ax.text(7.5, 6.55, "一次有效触点 Session 的采样流程", ha="center",
            fontsize=20, fontweight="bold", color="#14213D")
    nodes = [
        (0.35, "View\n已挂载", "attach"),
        (2.55, "生命周期\nRESUMED", "允许采样"),
        (4.75, "读取 View\n几何状态", "主线程"),
        (6.95, "计算可见率\n与遮挡率", "面积并"),
        (9.15, "连续状态机", "有效/无效"),
        (11.35, "构造 Report", "中间报告"),
        (13.55, "完成并停止", "一次性"),
    ]
    for x, title, subtitle in nodes:
        box(ax, x, 3.55, 1.35, 1.1, title, subtitle, "#EAF2FF")
    for i in range(len(nodes) - 1):
        arrow(ax, (nodes[i][0] + 1.35, 4.1), (nodes[i + 1][0], 4.1))

    ax.add_patch(FancyArrowPatch((7.65, 3.55), (7.65, 1.4), arrowstyle="-|>",
                                 mutation_scale=15, linewidth=1.5, color="#D64545"))
    ax.text(7.85, 2.25, "不满足可见阈值", fontsize=10, color="#B52D2D", rotation=90,
            ha="center", va="center")
    box(ax, 5.85, 0.35, 3.6, 0.85, "清零连续时长", "下一次有效帧重新建立起点", "#FFE7E7")

    ax.add_patch(FancyArrowPatch((3.2, 3.55), (3.2, 1.4), arrowstyle="-|>",
                                 mutation_scale=15, linewidth=1.5, color="#D64545"))
    ax.text(3.42, 2.25, "PAUSED / 脱离 Window", fontsize=9.5, color="#B52D2D", rotation=90,
            ha="center", va="center")
    box(ax, 1.35, 0.35, 3.7, 0.85, "暂停采样并清零", "恢复挂载且 RESUMED 后继续", "#FFF4D6")
    ax.text(12.1, 1.05, "completed=true\n只回调一次", ha="center", va="center",
            fontsize=11, fontweight="bold", color="#277A4C")
    fig.savefig(IMAGE_DIR / "viewability-lifecycle-flow.png", bbox_inches="tight")
    plt.close(fig)


def occlusion():
    """生成遮挡面积并集与扫描线示意图。"""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6.5), dpi=180,
                                   gridspec_kw={"width_ratios": [1.15, 1]})
    fig.suptitle("遮挡面积计算：矩形并集与扫描线", fontsize=20, fontweight="bold",
                 color="#14213D")
    ax1.set_xlim(0, 10)
    ax1.set_ylim(0, 8)
    ax1.set_aspect("equal")
    ax1.axis("off")
    ax1.text(5, 7.55, "目标 View 与遮挡矩形", ha="center", fontsize=14,
             fontweight="bold", color="#17315F")
    ax1.add_patch(Rectangle((1, 1), 7, 5, facecolor="#DCEBFF", edgecolor="#2F63C7", linewidth=2))
    ax1.text(4.5, 6.25, "目标矩形 T，面积 A = W × H", ha="center", fontsize=11, color="#17315F")
    ax1.add_patch(Rectangle((2.0, 3.5), 3.8, 2.0, facecolor="#E87575", alpha=0.78,
                            edgecolor="#A52B2B", linewidth=1.5))
    ax1.text(3.9, 4.5, "遮挡 R1", ha="center", va="center", fontsize=11, color="#6D1A1A")
    ax1.add_patch(Rectangle((4.7, 1.6), 2.6, 2.5, facecolor="#F2B45E", alpha=0.82,
                            edgecolor="#A96713", linewidth=1.5))
    ax1.text(6.0, 2.85, "遮挡 R2", ha="center", va="center", fontsize=11, color="#70420C")
    ax1.text(4.5, 0.35, "|U| = |(R1 ∪ R2) ∩ T|，重叠区只扣除一次", ha="center",
             fontsize=11, color="#42526B")

    ax2.set_xlim(0, 10)
    ax2.set_ylim(0, 8)
    ax2.axis("off")
    ax2.text(5, 7.55, "坐标压缩 + 扫描线", ha="center", fontsize=14,
             fontweight="bold", color="#17315F")
    xs = [1.2, 2.5, 4.0, 5.0, 6.6, 8.3]
    for x in xs:
        ax2.plot([x, x], [2.0, 5.8], color="#9AA8BC", linewidth=0.8, linestyle="--")
        ax2.text(x, 1.65, f"x={x:.1f}", ha="center", fontsize=9, color="#52627A")
    ax2.plot([1.0, 8.6], [2.0, 2.0], color="#52627A", linewidth=1.2)
    ax2.plot([1.0, 8.6], [5.8, 5.8], color="#52627A", linewidth=1.2)
    ax2.add_patch(Rectangle((2.5, 4.25), 3.1, 1.55, facecolor="#E87575", alpha=0.8))
    ax2.add_patch(Rectangle((5.0, 2.2), 3.3, 2.05, facecolor="#F2B45E", alpha=0.8))
    ax2.text(5, 6.45, "按 x 事件排序，每个区间维护 y 轴覆盖长度", ha="center", fontsize=10.5,
             color="#42526B")
    ax2.text(5, 0.65, "面积 = Σ（当前覆盖 y 长度 × 相邻 x 间距）", ha="center",
             fontsize=12, fontweight="bold", color="#17315F")
    fig.savefig(IMAGE_DIR / "viewability-occlusion-math.png", bbox_inches="tight")
    plt.close(fig)


def formulas():
    """生成文章中统一引用的公式汇总图，避免 Markdown 数学渲染差异。"""
    fig, ax = plt.subplots(figsize=(14, 8), dpi=180)
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 8)
    ax.axis("off")
    ax.text(7, 7.45, "ViewAbility 有效触点核心数学模型", ha="center",
            fontsize=21, fontweight="bold", color="#14213D")

    cards = [
        (0.65, 5.45, "窗口内有效区域", r"$I = T \cap V \cap W_n$", "代码：clippedVisibleRect = intersect(...)"),
        (7.15, 5.45, "窗口外覆盖面积", r"$A_{\mathrm{out}} = \max(0, A - |I|)$", "代码：outsideWindowArea"),
        (0.65, 3.05, "遮挡矩形并集", r"$U = \bigcup_{i=1}^{n}(R_i \cap T)$", "代码：collectVisibleOpaqueRects + unionArea"),
        (7.15, 3.05, "可见比例", r"$\mathrm{visibleRatio} = 1 - \frac{\min(A, A_{\mathrm{out}}+|U|)}{A}$", "代码：coveredArea 与 visibleRatio"),
        (0.65, 0.65, "有效透明度", r"$\alpha_{\mathrm{eff}} = \prod_{v \in path} \alpha(v)$", "代码：effectiveAlpha(view)"),
        (7.15, 0.65, "连续曝光", r"$D_k = t_k - s,\quad completed \Longleftrightarrow D_k \geq \tau$", "代码：ContinuousVisibilityState.record"),
    ]
    for x, y, title, formula, description in cards:
        patch = FancyBboxPatch(
            (x, y), 6.0, 1.55, boxstyle="round,pad=0.02,rounding_size=0.02",
            linewidth=1.4, edgecolor="#356AE6", facecolor="#EEF4FF",
        )
        ax.add_patch(patch)
        ax.text(x + 3.0, y + 1.18, title, ha="center", va="center",
                fontsize=12.5, fontweight="bold", color="#17315F")
        ax.text(x + 3.0, y + 0.76, formula, ha="center", va="center",
                fontsize=14, color="#1E3A70")
        ax.text(x + 3.0, y + 0.28, description, ha="center", va="center",
                fontsize=9.5, color="#42526B")
    fig.savefig(IMAGE_DIR / "viewability-formulas.png", bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    font_manager.fontManager.addfont(FONT_PATH)
    plt.rcParams["font.family"] = font_manager.FontProperties(fname=FONT_PATH).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    architecture()
    lifecycle()
    occlusion()
    formulas()
