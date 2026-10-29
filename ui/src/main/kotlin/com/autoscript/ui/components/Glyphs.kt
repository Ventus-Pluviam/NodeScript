package com.autoscript.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页签图标（§6 底栏）：**本仓不引图标依赖，这几个图标是画出来的**。
 *
 * 为什么画而不是拉一套图标包：底栏只用得到四个字形，而任何一套图标库（material-icons
 * 那类）都要为一个 24dp 的矢量多带一份依赖 + 一份许可声明，且它们的画风（Material 的
 * 圆头粗线）与 TG 的观感并不一致。这几个按 TG 底栏的语法自己画：**一格一形、线性等宽、
 * 选中靠染色而不是换形**，线条粗细与留白按同一个比例（见 [StrokeRatio]）。
 *
 * 形状用的都是最简单的几何（房子 = 折线 + 折线、时钟 = 圆 + 两针、终端 = 圆角矩形 +
 * 折线 + 短横、盾牌 = 六边形），在这四个形状上"像不像"的容错很大 —— 反倒是照抄某个
 * 图标库的路径数据更可能在 24dp 下发糊。
 *
 * 画在 0..1 的归一化坐标里再乘实际尺寸：同一份形状在 24dp 的底栏、20dp 的紧凑位都成立，
 * 不必为每个尺寸调一遍坐标。
 */
enum class GlyphKind {
    /** 首屏（壳/保活）：房子。 */
    HOME,

    /** 任务中心（定时/排期）：时钟。 */
    TASKS,

    /** 控制台（命令行）：终端窗口。 */
    CONSOLE,

    /** 无障碍服务：人形。 */
    ACCESSIBILITY,

    /** 屏幕采集：显示器。 */
    SCREEN,

    /** 悬浮窗：叠层方块。 */
    OVERLAY,

    /** 包裹/盲盒（依赖页页头的圆形图标，第十四批第 35 条）。 */
    PACKAGE,

    /** 通知：铃铛。 */
    BELL,

    /** root：盾牌。 */
    SHIELD,

    /** 设置页签（底栏第 4 格）：齿轮。 */
    SETTINGS,

    /** 搜索（项目页搜索栏左端的放大镜）。 */
    SEARCH,

    /** JS 脚本文件：圆角方块内一对尖括号。 */
    FILE_JS,

    /** Markdown/文本文档：文档页 + 折角。 */
    FILE_DOC,

    /** 网页文件：地球（圆 + 经纬线）。 */
    FILE_HTML,

    /** Shell 脚本：命令行提示符块。 */
    FILE_SH,

    /** 其余类型（二进制/数据/无扩展名）：立方体。 */
    FILE_GENERIC,

    /** 文件夹（项目页目录行）：折角文件夹。 */
    FOLDER,

    /** 新建（FAB 主按钮）：铅笔。 */
    PENCIL,

    /** 立即执行（任务行尾的实心播放三角）。 */
    PLAY,

    /** 执行脚本（项目页文件行尾那颗 ▶ —— 与 [PLAY] 同一颗三角，只是语义是"跑这个文件"）。 */
    RUN,

    /** 排序切换钮——切到**按字母**（三条横杠 + 字母 A；TG `msg_contacts_name`）。 */
    SORT_NAME,

    /** 排序切换钮——切到**按时间**（三条横杠 + 时钟；TG `msg_contacts_time`）。 */
    SORT_TIME,

    /** 搜索框放大镜（TG `FragmentSearchField` 用的 `outline_search_1_24`：粗环短柄）。 */
    SEARCH_FIELD,

    /** 展开/收起三角（TG `arrow_more`：CollapseTextCell 右侧的下尖 chevron）。 */
    CHEVRON,

    /** 使用情况访问权限（批 48）：三根高低不同的柱子 —— 「哪个应用用了多久」就是这么读的。 */
    CHART,

    /** 「更多」（文件/文件夹行尾的溢出钮）：三个竖排实心点（TG `ic_ab_other`）。 */
    MORE_VERT,

    /** 暂停（执行日志悬浮窗「暂停执行」钮）：两条竖杠。 */
    PAUSE,

    /** 继续（已暂停时的同一颗钮位）：右侧三角（与 [PLAY] 同形，悬浮窗内自足）。 */
    RESUME,

    /** 结束脚本（悬浮窗顶栏「结束脚本」钮）：实心方块 —— 停止的通用字形。 */
    STOP,

    /** 清空（悬浮窗「清空日志」钮）：垃圾桶（桶身 + 桶盖 + 两条竖纹）。 */
    CLEAR,

    /** 关闭（悬浮窗「关掉悬浮窗」钮）：✕（两条对角，ActionBar 选择模式同款字形）。 */
    CLOSE,

    /** 全屏（悬浮窗「打开全屏」钮）：四角向外（展开；已全屏时同一颗钮画反向）。 */
    FULLSCREEN,

    /** 发送（悬浮窗底部输入框右侧那颗箭头）：一条横杆 + 箭头。 */
    SEND,

    /** 重装（依赖列表每行那颗 ⟳）：开口圆环 + 弧端箭头。 */
    REFRESH,

    /** 依赖去重（依赖页「依赖去重」那颗选项）：三层叠片 —— 重复的东西拍成一层。 */
    LAYERS,

    /** 清理缓存（依赖页「清理缓存」那颗选项）：圆柱（数据/缓存盘的通用字形）。 */
    CACHE,
}

/** 线宽 ÷ 图标边长。四个字形共用一条，粗细才不会一格一个样。 */
private const val StrokeRatio = 0.085f

/**
 * 各字形折线的**归一化坐标**（类加载时建一次，绘制期零分配 —— 见 [Glyph] 的 KDoc）。
 *
 * 一条常量就是原来的一个 `path(…)` 调用点，数值逐字相同：`Array<out Pair<Float, Float>>`
 * 里每个点都是 `P_` 名下那个 `x to y`。命名 `<字形>_<笔的读法>`（`P_HOME_ROOF` = 屋顶那笔），
 * 几何只存在这一处 —— 改形状改这里，调用点不动。
 */
// 房子：屋顶（三控制点折线）。
private val P_HOME_ROOF = arrayOf(0.09f to 0.48f, 0.50f to 0.13f, 0.91f to 0.48f)
/** 房子：屋身（左下→右下→右侧竖边，闭合不要求 —— 首尾本来就是端点）。 */
private val P_HOME_BODY = arrayOf(0.23f to 0.45f, 0.23f to 0.87f, 0.77f to 0.87f, 0.77f to 0.45f)
/** 时钟：时针（朝上）。 */
private val P_TASKS_HOUR = arrayOf(0.50f to 0.50f, 0.50f to 0.27f)
/** 时钟：分针（朝右下）。 */
private val P_TASKS_MINUTE = arrayOf(0.50f to 0.50f, 0.69f to 0.61f)
/** 终端：提示符 ">"。 */
private val P_CONSOLE_PROMPT = arrayOf(0.30f to 0.40f, 0.42f to 0.50f, 0.30f to 0.60f)
/** 终端：光标 "_"。 */
private val P_CONSOLE_CURSOR = arrayOf(0.54f to 0.60f, 0.71f to 0.60f)
/** 盾牌：六边形外周（上宽下尖）。 */
private val P_SHIELD_BODY = arrayOf(0.50f to 0.11f, 0.87f to 0.25f, 0.87f to 0.51f, 0.50f to 0.89f, 0.13f to 0.51f, 0.13f to 0.25f)
/** 无障碍：平举的双臂（一横）。 */
private val P_A11Y_ARMS = arrayOf(0.20f to 0.34f, 0.80f to 0.34f)
/** 无障碍：躯干（一竖）。 */
private val P_A11Y_BODY = arrayOf(0.50f to 0.34f, 0.50f to 0.62f)
/** 无障碍：左臂下行（手自然下垂那一笔）。 */
private val P_A11Y_ARM_L = arrayOf(0.50f to 0.62f, 0.36f to 0.88f)
/** 无障碍：右臂下行（手自然下垂那一笔）。 */
private val P_A11Y_ARM_R = arrayOf(0.50f to 0.62f, 0.64f to 0.88f)
/** 屏幕采集：底座竖颈。 */
private val P_SCREEN_NECK = arrayOf(0.50f to 0.70f, 0.50f to 0.82f)
/** 屏幕采集：底座横条。 */
private val P_SCREEN_BASE = arrayOf(0.34f to 0.84f, 0.66f to 0.84f)
/** 悬浮窗：底层方块。 */
private val P_OVERLAY_BACK = arrayOf(0.14f to 0.30f, 0.62f to 0.30f, 0.62f to 0.78f, 0.14f to 0.78f)
/** 悬浮窗：右上叠出的小方块（三笔折线，不开底 —— 层级感）。 */
private val P_OVERLAY_FRONT = arrayOf(0.38f to 0.22f, 0.86f to 0.22f, 0.86f to 0.70f)
/** 设置：上齿（短刺）。 */
private val P_SETTINGS_TEETH_UP = arrayOf(0.50f to 0.08f, 0.50f to 0.20f)
/** 设置：下齿（短刺）。 */
private val P_SETTINGS_TEETH_DOWN = arrayOf(0.50f to 0.80f, 0.50f to 0.92f)
/** 设置：左齿（短刺）。 */
private val P_SETTINGS_TEETH_LEFT = arrayOf(0.08f to 0.50f, 0.20f to 0.50f)
/** 设置：右齿（短刺）。 */
private val P_SETTINGS_TEETH_RIGHT = arrayOf(0.80f to 0.50f, 0.92f to 0.50f)
/** 搜索：放大镜斜柄。 */
private val P_SEARCH_HANDLE = arrayOf(0.65f to 0.65f, 0.86f to 0.86f)
/** JS 徽标：左尖括号。 */
private val P_FILE_JS_LT = arrayOf(0.42f to 0.40f, 0.30f to 0.50f, 0.42f to 0.60f)
/** JS 徽标：右尖括号。 */
private val P_FILE_JS_GT = arrayOf(0.58f to 0.40f, 0.70f to 0.50f, 0.58f to 0.60f)
/** 文档：竖长页轮廓（右下折角起点在 0.76,0.26 —— 折角线由 [P_FILE_DOC_FOLD] 收口）。 */
private val P_FILE_DOC_BODY = arrayOf(0.24f to 0.10f, 0.62f to 0.10f, 0.76f to 0.26f, 0.76f to 0.90f, 0.24f to 0.90f)
/** 文档：右上折角。 */
private val P_FILE_DOC_FOLD = arrayOf(0.62f to 0.10f, 0.62f to 0.26f, 0.76f to 0.26f)
/** 文档：第一行文字线。 */
private val P_FILE_DOC_LINE1 = arrayOf(0.36f to 0.48f, 0.64f to 0.48f)
/** 文档：第二行文字线。 */
private val P_FILE_DOC_LINE2 = arrayOf(0.36f to 0.62f, 0.64f to 0.62f)
/** 网页：竖向经线。 */
private val P_FILE_HTML_MERIDIAN = arrayOf(0.50f to 0.12f, 0.50f to 0.88f)
/** 网页：上纬线。 */
private val P_FILE_HTML_PARALLEL1 = arrayOf(0.24f to 0.28f, 0.76f to 0.28f)
/** 网页：下纬线。 */
private val P_FILE_HTML_PARALLEL2 = arrayOf(0.24f to 0.72f, 0.76f to 0.72f)
/** 网页：赤道横线。 */
private val P_FILE_HTML_EQUATOR = arrayOf(0.12f to 0.50f, 0.88f to 0.50f)
/** Shell：终端提示符（与 [P_CONSOLE_PROMPT] 同形，无外框）。 */
private val P_FILE_SH_PROMPT = arrayOf(0.18f to 0.34f, 0.36f to 0.50f, 0.18f to 0.66f)
/** Shell：光标下横。 */
private val P_FILE_SH_DASH = arrayOf(0.44f to 0.66f, 0.70f to 0.66f)
/** 立方体：正面方块。 */
private val P_FILE_GENERIC_FRONT = arrayOf(0.16f to 0.28f, 0.58f to 0.28f, 0.58f to 0.72f, 0.16f to 0.72f)
/** 立方体：右侧面（体量感）。 */
private val P_FILE_GENERIC_SIDE = arrayOf(0.58f to 0.28f, 0.84f to 0.42f, 0.84f to 0.86f, 0.58f to 0.72f)
/** 铃铛：钟形外周。 */
private val P_BELL_BODY = arrayOf(0.50f to 0.14f, 0.22f to 0.42f, 0.22f to 0.68f, 0.78f to 0.68f, 0.78f to 0.42f, 0.50f to 0.14f)
/** 铃铛：底部小舌。 */
private val P_BELL_CLAPPER = arrayOf(0.42f to 0.82f, 0.58f to 0.82f)
/** 文件夹：背板 + 标签突起（闭合 —— 底边那一横不能少，批 25 定案）。 */
private val P_FOLDER_BODY = arrayOf(0.10f to 0.74f, 0.10f to 0.30f, 0.36f to 0.30f, 0.44f to 0.38f, 0.90f to 0.38f, 0.90f to 0.74f)
/** 铅笔：斜杆。 */
private val P_PENCIL_SHAFT = arrayOf(0.24f to 0.76f, 0.24f to 0.62f, 0.66f to 0.20f, 0.80f to 0.34f, 0.38f to 0.76f)
/** 铅笔：笔尖三角。 */
private val P_PENCIL_TIP = arrayOf(0.24f to 0.76f, 0.38f to 0.76f, 0.24f to 0.62f)
/** 铅笔：笔杆横线。 */
private val P_PENCIL_LINE = arrayOf(0.62f to 0.24f, 0.76f to 0.38f)
/** 播放三角（实心 —— PLAY 与 RUN 共用这一支，形态本来就该一模一样）。 */
private val P_PLAY_TRIANGLE = arrayOf(0.30f to 0.20f, 0.30f to 0.80f, 0.80f to 0.50f)
/** 排序切换：三条横杠共用的第一条（y≈0.23）。 */
private val P_SORT_LINE1 = arrayOf(0.11f to 0.23f, 0.75f to 0.23f)
/** 排序切换：三条横杠共用的第二条（y≈0.45）。 */
private val P_SORT_LINE2 = arrayOf(0.11f to 0.45f, 0.58f to 0.45f)
/** 排序切换：三条横杠共用的第三条（y≈0.67）。 */
private val P_SORT_LINE3 = arrayOf(0.11f to 0.67f, 0.44f to 0.67f)
/** 排序切换（按时间档）：字母 A 的主体（"切到字母"那档的尾缀）。 */
private val P_SORT_LETTER_A = arrayOf(0.62f to 0.80f, 0.74f to 0.50f, 0.86f to 0.80f)
/** 排序切换（按时间档）：字母 A 的横杠。 */
private val P_SORT_LETTER_BAR = arrayOf(0.655f to 0.70f, 0.825f to 0.70f)
/** 排序切换（按字母档）：时钟分针。 */
private val P_SORT_TIME_HAND = arrayOf(0.755f to 0.655f, 0.755f to 0.545f)
/** 排序切换（按字母档）：时钟右侧小针。 */
private val P_SORT_TIME_PIVOT = arrayOf(0.755f to 0.655f, 0.83f to 0.655f)
/** 搜索框放大镜：斜柄（比 [P_SEARCH_HANDLE] 短一档，粗环配短柄）。 */
private val P_SEARCH_FIELD_HANDLE = arrayOf(0.62f to 0.56f, 0.786f to 0.728f)
/** 展开/收起三角（arrow_more 的下尖 chevron）。 */
private val P_CHEVRON = arrayOf(0.19f to 0.36f, 0.50f to 0.70f, 0.81f to 0.36f)
/** 柱状图：左柱。 */
private val P_CHART_L = arrayOf(0.17f to 0.84f, 0.17f to 0.52f)
/** 柱状图：中柱（最高）。 */
private val P_CHART_M = arrayOf(0.50f to 0.84f, 0.50f to 0.24f)
/** 柱状图：右柱。 */
private val P_CHART_R = arrayOf(0.83f to 0.84f, 0.83f to 0.42f)
/** 暂停：左竖杠。 */
private val P_PAUSE_L = arrayOf(0.36f to 0.22f, 0.36f to 0.78f)
/** 暂停：右竖杠。 */
private val P_PAUSE_R = arrayOf(0.64f to 0.22f, 0.64f to 0.78f)
/** 继续：右侧实心三角（[P_PLAY_TRIANGLE] 同形、右移 —— 悬浮窗内自足）。 */
private val P_RESUME_TRIANGLE = arrayOf(0.34f to 0.22f, 0.34f to 0.78f, 0.76f to 0.50f)
/** 停止：实心方块。 */
private val P_STOP_SQUARE = arrayOf(0.31f to 0.31f, 0.69f to 0.31f, 0.69f to 0.69f, 0.31f to 0.69f)
/** 清空：桶盖横线。 */
private val P_CLEAR_LID = arrayOf(0.28f to 0.28f, 0.72f to 0.28f)
/** 清空：提手。 */
private val P_CLEAR_HANDLE = arrayOf(0.40f to 0.28f, 0.40f to 0.20f, 0.60f to 0.20f, 0.60f to 0.28f)
/** 清空：梯形桶身。 */
private val P_CLEAR_BIN = arrayOf(0.32f to 0.28f, 0.68f to 0.28f, 0.62f to 0.82f, 0.38f to 0.82f)
/** 清空：桶身左竖纹。 */
private val P_CLEAR_GROOVE_L = arrayOf(0.45f to 0.40f, 0.45f to 0.70f)
/** 清空：桶身右竖纹。 */
private val P_CLEAR_GROOVE_R = arrayOf(0.55f to 0.40f, 0.55f to 0.70f)
/** 关闭：✕ 第一条对角线。 */
private val P_CLOSE_DIAG_1 = arrayOf(0.28f to 0.28f, 0.72f to 0.72f)
/** 关闭：✕ 第二条对角线。 */
private val P_CLOSE_DIAG_2 = arrayOf(0.72f to 0.28f, 0.28f to 0.72f)
/** 全屏：左上角。 */
private val P_FULLSCREEN_TL = arrayOf(0.32f to 0.42f, 0.22f to 0.32f, 0.22f to 0.22f, 0.32f to 0.22f)
/** 全屏：右上角。 */
private val P_FULLSCREEN_TR = arrayOf(0.68f to 0.42f, 0.78f to 0.32f, 0.78f to 0.22f, 0.68f to 0.22f)
/** 全屏：左下角。 */
private val P_FULLSCREEN_BL = arrayOf(0.32f to 0.58f, 0.22f to 0.68f, 0.22f to 0.78f, 0.32f to 0.78f)
/** 全屏：右下角。 */
private val P_FULLSCREEN_BR = arrayOf(0.68f to 0.58f, 0.78f to 0.68f, 0.78f to 0.78f, 0.68f to 0.78f)
/** 盲盒/包裹：盒身四角（开口朝上的箱体轮廓）。 */
private val P_PACKAGE_BOX = arrayOf(0.24f to 0.36f, 0.24f to 0.80f, 0.76f to 0.80f, 0.76f to 0.36f)
/** 盲盒/包裹：盒盖沿（一条横线，把盖与身分开 —— 无盖就是普通方块）。 */
private val P_PACKAGE_LID = arrayOf(0.24f to 0.36f, 0.76f to 0.36f)
/** 盲盒/包裹：穿盒的竖丝带（一头冒出盒顶、一头露出盒底 —— 表示"整包系了一圈"）。 */
private val P_PACKAGE_RIB = arrayOf(0.50f to 0.22f, 0.50f to 0.92f)
/** 盲盒/包裹：丝带顶结（结心两侧各一笔向上的翼 —— 没有它只像一根尺子）。 */
private val P_PACKAGE_BOW = arrayOf(0.40f to 0.24f, 0.50f to 0.16f, 0.60f to 0.24f)
/** 发送：横杆。 */
private val P_SEND_BAR = arrayOf(0.16f to 0.50f, 0.80f to 0.50f)
/** 发送：箭头。 */
private val P_SEND_ARROW = arrayOf(0.58f to 0.28f, 0.82f to 0.50f, 0.58f to 0.72f)
/** 重装：弧端箭头（三点来历见 REFRESH 那支的 KDoc）。 */
private val P_REFRESH_ARROW = arrayOf(0.763f to 0.119f, 0.776f to 0.268f, 0.631f to 0.230f)
/** 叠层：顶上一枚菱形。 */
private val P_LAYERS_DIAMOND = arrayOf(0.50f to 0.10f, 0.90f to 0.32f, 0.50f to 0.54f, 0.10f to 0.32f)
/** 叠层：第一道 V 折线。 */
private val P_LAYERS_V1 = arrayOf(0.10f to 0.56f, 0.50f to 0.78f, 0.90f to 0.56f)
/** 叠层：第二道 V 折线。 */
private val P_LAYERS_V2 = arrayOf(0.10f to 0.74f, 0.50f to 0.96f, 0.90f to 0.74f)
/** 缓存：左侧边。 */
private val P_CACHE_SIDE_L = arrayOf(0.17f to 0.26f, 0.17f to 0.74f)
/** 缓存：右侧边。 */
private val P_CACHE_SIDE_R = arrayOf(0.83f to 0.26f, 0.83f to 0.74f)

/**
 * [Glyph] 一次绘制要用的临时对象 —— `Path` 与 `Stroke` 都留住复用（理由见 [Glyph] 的 KDoc）。
 *
 * 借出是**按顺序**的：每次绘制开头 [begin]，后面每个 `path()` 取池子里的下一条并 `reset()`。
 * 池子只增不减 —— 一个字形最多几笔，长到那个数就封顶，之后一帧一个新对象都不造。
 */
private class GlyphScratch {

    private val paths = ArrayList<Path>(4)
    private var used = 0

    private var cachedStroke: Stroke? = null
    private var cachedU = 0f
    private var cachedWeight = 0f

    /** 每次绘制开头调一次：把上一帧借出去的 Path 全部收回。 */
    fun begin() {
        used = 0
    }

    /** 取一条**空的** Path（池子不够才新建）。 */
    fun path(): Path {
        val reused = if (used < paths.size) paths[used] else Path().also { paths.add(it) }
        used++
        return reused.also { it.reset() }
    }

    /** 取这一帧的描边（线宽只跟 `u` 与 `weight` 有关，不必每帧重建）。 */
    fun stroke(u: Float, weight: Float): Stroke {
        val hit = cachedStroke
        if (hit != null && cachedU == u && cachedWeight == weight) return hit
        return Stroke(
            width = u * StrokeRatio * weight,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ).also {
            cachedStroke = it
            cachedU = u
            cachedWeight = weight
        }
    }
}

/**
 * 画一个页签图标。
 *
 * @param tint 线条色（调用方给：选中 = 强调色，未选中 = 次级灰）。
 * @param weight 线宽倍率。**选中项略微加粗** —— 这套图标是线性的，没有"填充版"可切，
 *   加粗就是这一档语言里的"实心"（TG 底栏选中项也是靠视觉重量拉开，不是靠颜色一条）。
 *   实心字形（[GlyphKind.MORE_VERT]）没有线宽可加，它缩放的是**点半径**（见那一格）。
 *
 * ## 为什么画的时候要自己拿一个池子（2026-10-18）
 *
 * 用户口径「依赖列表滑动起来帧率怎么只有60」。查下来这条绘制链路上**确有一处每帧分配**：
 * `Canvas` 的绘制 lambda **每一帧都会重跑一遍**（列表滚动时可见行每帧都要重画），而原来的
 * `path(...)` 是 `Path().apply { … }` —— **每画一笔就新建一个 `Path`**。Compose 的 `Path`
 * 背后是一个 Skia 原生对象，于是每一个可见字形、每一帧都在造 1~4 个原生对象再丢掉
 * （底栏 5 个 + 顶栏 + 依赖列表每一行的 ⟳…）。滚动时这是纯粹的白扔。
 *
 * 改法**不动任何一个字形的几何**：`path(...)` 改成从 [GlyphScratch] 里取一条**复用**的
 * `Path`（`reset()` 后重画），`Stroke` 也按 `(u, weight)` 缓存住。调用点一处没改，字形逐条
 * 与原来等价。
 *
 * **复用安全的前提**（都成立才敢这么做）：① 每个 `path(...)` 都是**立刻**喂给 `drawPath`，
 * 全文件没有 `val p = path(...)` 这种把两条 Path 同时留活的写法 —— 池子按顺序借出即可；
 * ② 池子按 `remember` 挂在**这一个** Canvas 节点上，不与别的字形共用；
 * ③ 绘制 lambda 在 UI 线程录制期跑，改的是自己上一步刚 reset 的那条 Path。
 *
 * ## 第二刀（2026-10-28）：坐标提成常量，绘制期零分配
 *
 * 池子只去掉了 `Path` 与 `Stroke` 的每帧分配，坐标那一路还漏着：`path(0.19f to 0.36f, …)`
 * 的 vararg 数组、`Pair` 与装箱的 `Float` 是**在调用点**现造的 —— 而 `when (kind)` 整段
 * 每帧都要重跑一遍，于是每个可见字形每帧仍要造十几个小对象（展开的依赖列表一行一颗
 * ⟳，滚动时就是每帧上百个）。GC 是「一段时间里攒出来的一次停顿」—— 帧率读屏看到的就是
 * 一阵一阵的卡。
 *
 * 改法：每一条 `path(...)` 的坐标整体提成文件级常量（`P_<字形>_<笔名>`，`Array<out
 * Pair<Float, Float>>`，类加载时建一次），`path()` 改收这份常量。调用点形状不变、
 * 数值逐字相同，只是不再有「每帧新建数组 + Pair + 装箱 Float」那一步 —— 绘制期零分配。
 */
@Composable
fun Glyph(
    kind: GlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    weight: Float = 1f,
) {
    val scratch = remember { GlyphScratch() }
    Canvas(modifier.size(size)) {
        scratch.begin()
        val u = this.size.minDimension
        val stroke = scratch.stroke(u, weight)
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun path(points: Array<out Pair<Float, Float>>): Path = scratch.path().apply {
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u)
            }
        }

        when (kind) {
            // 房子：屋顶两笔 + 屋身三笔（左下→右下→右侧竖边）。不画门 ——
            // 24dp 里再塞一扇门就只剩一团黑。
            GlyphKind.HOME -> {
                drawPath(path(P_HOME_ROOF), tint, style = stroke)
                drawPath(path(P_HOME_BODY), tint, style = stroke)
            }

            // 时钟：外圈 + 时针（朝上）+ 分针（朝右下）。任务是"什么时候跑"，钟面比清单更贴。
            GlyphKind.TASKS -> {
                drawCircle(tint, radius = 0.37f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(P_TASKS_HOUR), tint, style = stroke)
                drawPath(path(P_TASKS_MINUTE), tint, style = stroke)
            }

            // 终端：窗口外框 + 提示符 ">" + 光标 "_"。
            GlyphKind.CONSOLE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = at(0.09f, 0.17f),
                    size = Size(0.82f * u, 0.66f * u),
                    cornerRadius = CornerRadius(0.14f * u),
                    style = stroke,
                )
                drawPath(path(P_CONSOLE_PROMPT), tint, style = stroke)
                drawPath(path(P_CONSOLE_CURSOR), tint, style = stroke)
            }

            // 盾牌：上宽下尖的六边形。root/授权类条目管的是"开/关"，盾牌最不容易读错。
            GlyphKind.SHIELD -> {
                drawPath(path(P_SHIELD_BODY).apply { close() }, tint, style = stroke)
            }

            // 无障碍：头（圆）+ 躯干（一竖）+ 平举的双臂（一横）。
            GlyphKind.ACCESSIBILITY -> {
                drawCircle(tint, radius = 0.09f * u, center = at(0.5f, 0.16f), style = stroke)
                drawPath(path(P_A11Y_ARMS), tint, style = stroke)
                drawPath(path(P_A11Y_BODY), tint, style = stroke)
                drawPath(path(P_A11Y_ARM_L), tint, style = stroke)
                drawPath(path(P_A11Y_ARM_R), tint, style = stroke)
            }

            // 屏幕采集：显示器外框（圆角矩形）+ 底座（短横 + 竖颈）。
            GlyphKind.SCREEN -> {
                drawRoundRect(
                    color = tint,
                    topLeft = at(0.10f, 0.16f),
                    size = Size(0.80f * u, 0.54f * u),
                    cornerRadius = CornerRadius(0.10f * u),
                    style = stroke,
                )
                drawPath(path(P_SCREEN_NECK), tint, style = stroke)
                drawPath(path(P_SCREEN_BASE), tint, style = stroke)
            }

            // 悬浮窗：底层方块 + 右上叠出的小方块（层级感）。
            GlyphKind.OVERLAY -> {
                drawPath(path(P_OVERLAY_BACK).apply { close() }, tint, style = stroke)
                drawPath(path(P_OVERLAY_FRONT), tint, style = stroke)
            }

            // 设置：齿轮 = 外圈（圆）+ 齿（四向短刺）+ 中孔（小圆）。
            GlyphKind.SETTINGS -> {
                drawCircle(tint, radius = 0.30f * u, center = at(0.5f, 0.5f), style = stroke)
                drawCircle(tint, radius = 0.10f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(P_SETTINGS_TEETH_UP), tint, style = stroke)
                drawPath(path(P_SETTINGS_TEETH_DOWN), tint, style = stroke)
                drawPath(path(P_SETTINGS_TEETH_LEFT), tint, style = stroke)
                drawPath(path(P_SETTINGS_TEETH_RIGHT), tint, style = stroke)
            }

            // 搜索：放大镜 = 斜柄 + 圆环（环心偏离几何中心，柄在右下）。
            GlyphKind.SEARCH -> {
                drawCircle(tint, radius = 0.28f * u, center = at(0.44f, 0.44f), style = stroke)
                drawPath(path(P_SEARCH_HANDLE), tint, style = stroke)
            }

            // JS：圆角方块（语言徽标的底）+ 内嵌一对尖括号 < >。
            GlyphKind.FILE_JS -> {
                drawRoundRect(
                    color = tint,
                    topLeft = at(0.12f, 0.12f),
                    size = Size(0.76f * u, 0.76f * u),
                    cornerRadius = CornerRadius(0.16f * u),
                    style = stroke,
                )
                drawPath(path(P_FILE_JS_LT), tint, style = stroke)
                drawPath(path(P_FILE_JS_GT), tint, style = stroke)
            }

            // 文档：竖长页 + 右上折角 + 两行文字线。
            GlyphKind.FILE_DOC -> {
                drawPath(path(P_FILE_DOC_BODY).apply { close() }, tint, style = stroke)
                drawPath(path(P_FILE_DOC_FOLD), tint, style = stroke)
                drawPath(path(P_FILE_DOC_LINE1), tint, style = stroke)
                drawPath(path(P_FILE_DOC_LINE2), tint, style = stroke)
            }

            // 网页：地球 = 外圆 + 竖向椭圆（两条经线的近似）+ 赤道横线。
            GlyphKind.FILE_HTML -> {
                drawCircle(tint, radius = 0.38f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(P_FILE_HTML_MERIDIAN), tint, style = stroke)
                drawPath(path(P_FILE_HTML_PARALLEL1), tint, style = stroke)
                drawPath(path(P_FILE_HTML_PARALLEL2), tint, style = stroke)
                drawPath(path(P_FILE_HTML_EQUATOR), tint, style = stroke)
            }

            // Shell：终端提示符（与 CONSOLE 同形的简化版，少了外框 —— 文件图标要满框）。
            GlyphKind.FILE_SH -> {
                drawPath(path(P_FILE_SH_PROMPT), tint, style = stroke)
                drawPath(path(P_FILE_SH_DASH), tint, style = stroke)
            }

            // 其余：立方体 = 正面方块 + 右侧面（体量感）。
            GlyphKind.FILE_GENERIC -> {
                drawPath(path(P_FILE_GENERIC_FRONT).apply { close() }, tint, style = stroke)
                drawPath(path(P_FILE_GENERIC_SIDE), tint, style = stroke)
            }

            // 通知：铃铛（钟形）+ 顶部小柄 + 底部小舌。
            GlyphKind.BELL -> {
                drawPath(path(P_BELL_BODY), tint, style = stroke)
                drawPath(path(P_BELL_CLAPPER), tint, style = stroke)
            }

            // 文件夹：折角文件夹（TG menu_folder_add 的轮廓读法：背板 + 标签突起）。
            // 首尾闭合（close）—— 底边那一条横线不能少：开放路径在 24dp 下读起来就是
            // "没合上的文件夹"（批 25 实机截图定案的形状修正）。
            GlyphKind.FOLDER -> {
                drawPath(path(P_FOLDER_BODY).apply { close() }, tint, style = stroke)
            }

            // 盲盒/包裹（依赖页页头）：盒身 + 盖沿 + 竖丝带 + 顶结。盒身**首尾闭合**：
            // 底边横线不能少（与 FOLDER 同一条教训），四条边读成"一只箱"而不是"凹槽"。
            // 丝带与结是不闭合的折线 —— 它们是绕在盒子上的，不该连成一块。
            GlyphKind.PACKAGE -> {
                drawPath(path(P_PACKAGE_BOX).apply { close() }, tint, style = stroke)
                drawPath(path(P_PACKAGE_LID), tint, style = stroke)
                drawPath(path(P_PACKAGE_RIB), tint, style = stroke)
                drawPath(path(P_PACKAGE_BOW), tint, style = stroke)
            }

            // 铅笔（FAB 主钮）：斜杆 + 笔尖三角 + 笔杆横线（TG floating_pencil 的线性读法）。
            GlyphKind.PENCIL -> {
                drawPath(path(P_PENCIL_SHAFT), tint, style = stroke)
                drawPath(path(P_PENCIL_TIP), tint, style = stroke)
                drawPath(path(P_PENCIL_LINE), tint, style = stroke)
            }

            // 播放三角（任务行尾「立即执行」/ 项目页文件行尾「运行」）：实心
            // （TG 这颗是填充形，线性描边读不出"按了会跑"）。两档共用这一支 ——
            // 形态本来就该一模一样，抄成两支迟早只改一支。
            GlyphKind.PLAY, GlyphKind.RUN -> {
                drawPath(
                    path(P_PLAY_TRIANGLE).apply { close() },
                    tint,
                    style = Fill,
                )
            }

            // 排序切换（TG msg_contacts_name/time 的 webp 实测几何：三条圆头横杠
            // y≈0.23/0.45/0.67，长度递减 x→0.75/0.58/0.44；尾缀 = 切过去的那一档——
            // 按时间排序时显示字母 A（切到字母），按字母时显示时钟（切到时间），与 TG 同款）。
            GlyphKind.SORT_NAME -> {
                drawPath(path(P_SORT_LINE1), tint, style = stroke)
                drawPath(path(P_SORT_LINE2), tint, style = stroke)
                drawPath(path(P_SORT_LINE3), tint, style = stroke)
                drawPath(path(P_SORT_LETTER_A), tint, style = stroke)
                drawPath(path(P_SORT_LETTER_BAR), tint, style = stroke)
            }

            GlyphKind.SORT_TIME -> {
                drawPath(path(P_SORT_LINE1), tint, style = stroke)
                drawPath(path(P_SORT_LINE2), tint, style = stroke)
                drawPath(path(P_SORT_LINE3), tint, style = stroke)
                drawCircle(tint, radius = 0.165f * u, center = at(0.755f, 0.655f), style = stroke)
                drawPath(path(P_SORT_TIME_HAND), tint, style = stroke)
                drawPath(path(P_SORT_TIME_PIVOT), tint, style = stroke)
            }

            // 搜索框放大镜（outline_search_1_24 的 72 视口实测：环心 (0.44, 0.427)、
            // 外半径 16.02/72≈0.222、环宽 4.6/72≈0.064（≈StrokeRatio 的 0.75 倍 ——
            // 这颗比底栏那颗 SEARCH 粗一档），柄 (0.62,0.56)→(0.786,0.728)）。
            // 与 [SEARCH] 不是同一颗：那颗环更细、柄更长，是菜单项的放大镜。
            GlyphKind.SEARCH_FIELD -> {
                drawCircle(
                    tint,
                    radius = (0.222f - 0.032f) * u,
                    center = at(0.44f, 0.427f),
                    style = Stroke(width = 0.064f * u, cap = StrokeCap.Round),
                )
                drawPath(path(P_SEARCH_FIELD_HANDLE), tint, style = stroke)
            }

            // 展开/收起三角（arrow_more 的 webp 实测：下尖 chevron，三控制点
            // (0.19,0.36)→(0.50,0.70)→(0.81,0.36)，笔画 6/72≈0.083 ≈ StrokeRatio）。
            // 收起 = 尖朝下（原样），展开 = 转 180° 尖朝上（CollapseTextCell 的
            // `rotation(collapsed ? 0 : 180)` 同语义）。
            GlyphKind.CHEVRON -> {
                drawPath(path(P_CHEVRON), tint, style = stroke)
            }

            // 柱状图：三根圆头柱子（中柱最高）。不画坐标轴 —— 24dp 里轴线与柱子
            // 共用同一份 StrokeRatio 笔画，会糊成一张表格。
            GlyphKind.CHART -> {
                drawPath(path(P_CHART_L), tint, style = stroke)
                drawPath(path(P_CHART_M), tint, style = stroke)
                drawPath(path(P_CHART_R), tint, style = stroke)
            }

            // 「更多」三点（TG `ic_ab_other` 的实测：三点竖排、半径 2.1/24≈0.088、
            // 圆心 y = 4.5/12/19.5 ÷ 24 ≈ 0.19/0.50/0.81）。**实心**不是描边 ——
            // 0.088 的小圆用 StrokeRatio 描边会糊成一圈灰环，看不出是三个点。
            // `weight` 在这一格缩放的是**点半径**（实心圆没有线宽）：TG 的 `ic_ab_other`
            // 只有一档粗细，本仓要两档 —— 顶栏那颗最粗、列表行尾那颗最细，
            // 见 [BAR_MORE_DOTS_WEIGHT] / [ROW_MORE_DOTS_WEIGHT] 那组常量。
            GlyphKind.MORE_VERT -> {
                val dot = 0.088f * u * weight
                drawCircle(tint, radius = dot, center = at(0.5f, 0.19f))
                drawCircle(tint, radius = dot, center = at(0.5f, 0.50f))
                drawCircle(tint, radius = dot, center = at(0.5f, 0.81f))
            }

            // 暂停：两条竖杠（与 RESUME 同一钮位，状态切换不断布局）。
            GlyphKind.PAUSE -> {
                drawPath(path(P_PAUSE_L), tint, style = stroke)
                drawPath(path(P_PAUSE_R), tint, style = stroke)
            }

            // 继续：右侧实心三角（PLAY 同形 —— 悬浮窗内没有播放语境，不会读错）。
            GlyphKind.RESUME -> {
                drawPath(path(P_RESUME_TRIANGLE).apply { close() }, tint)
            }

            // 结束脚本：实心方块（停止的通用字形 —— 与 PLAY/RUN 那颗三角互为对偶，
            // 窗里两颗都在时一眼读得出"这颗是停"）。画法照 PLAY：实心而非描边。
            GlyphKind.STOP -> {
                drawPath(path(P_STOP_SQUARE).apply { close() }, tint, style = Fill)
            }

            // 清空：垃圾桶 = 桶盖横线 + 提手 + 梯形桶身 + 两条竖纹。
            GlyphKind.CLEAR -> {
                drawPath(path(P_CLEAR_LID), tint, style = stroke)
                drawPath(path(P_CLEAR_HANDLE), tint, style = stroke)
                drawPath(path(P_CLEAR_BIN).apply { close() }, tint, style = stroke)
                drawPath(path(P_CLEAR_GROOVE_L), tint, style = stroke)
                drawPath(path(P_CLEAR_GROOVE_R), tint, style = stroke)
            }

            // 关闭：✕ 两条对角（ActionBar 选择模式的 `backGlyph = "✕"` 同款字形，画出来）。
            GlyphKind.CLOSE -> {
                drawPath(path(P_CLOSE_DIAG_1), tint, style = stroke)
                drawPath(path(P_CLOSE_DIAG_2), tint, style = stroke)
            }

            // 全屏：四角向外（四段折线，各自朝角落；已全屏时同一颗钮含义为"退出全屏"，字形不变）。
            GlyphKind.FULLSCREEN -> {
                drawPath(path(P_FULLSCREEN_TL), tint, style = stroke)
                drawPath(path(P_FULLSCREEN_TR), tint, style = stroke)
                drawPath(path(P_FULLSCREEN_BL), tint, style = stroke)
                drawPath(path(P_FULLSCREEN_BR), tint, style = stroke)
            }

            // 发送：一条横杆 + 右侧箭头。**不用纸飞机** —— 那是"发消息出去"的语义，
            // 而这一颗是"把这一行送进正在跑的那个脚本"，一支直白的箭头最不容易读错
            // （与 `arrow_more` 那种装饰性 chevron 也分得开：它有杆）。
            GlyphKind.SEND -> {
                drawPath(path(P_SEND_BAR), tint, style = stroke)
                drawPath(path(P_SEND_ARROW), tint, style = stroke)
            }

            // 重装：开口圆环（40°→320°，缺口在右侧）+ 弧端一支箭头 —— 「同一件事再来一遍」
            // 就是这个字形在说的话。**开口 + 箭头两样都要**：只画一个整圆会被读成"环形进度"
            // 或"齿轮"，只画半圈又认不出方向。
            //
            // 箭头那三点的来历（不是随手挑的坐标）：弧的**终点**在 320°，即
            // `(0.5 + 0.36·cos320°, 0.5 + 0.36·sin320°) ≈ (0.776, 0.268)`；
            // 该点的切线（行进方向，顺时针）是 `(-sin320°, cos320°) ≈ (0.64, 0.77)`，
            // 两支倒刺就是从终点沿**反切线**转 ±35°、各退 0.15 得到
            // —— 于是"尖朝行进方向、V 口朝后"这句话在坐标上成立。
            // 18dp 下这条箭头只剩两三个像素，但它给出的**方向**比精度重要。
            GlyphKind.REFRESH -> {
                val r = 0.36f * u
                drawArc(
                    color = tint,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(0.5f * u - r, 0.5f * u - r),
                    size = Size(2 * r, 2 * r),
                    style = stroke,
                )
                drawPath(path(P_REFRESH_ARROW), tint, style = stroke)
            }

            // 叠层：顶上一枚菱形（四点闭合）+ 下面两道 V 折线。三笔的间距与那两道 V 的
            // 开口（0.10↔0.90）取自"叠片"的通用画法 —— 画成三条平行横线读起来是"列表"，
            // 画成三个方块是"网格"，只有菱形+折线这一个形态在 24dp 下还能读出"层"。
            GlyphKind.LAYERS -> {
                drawPath(path(P_LAYERS_DIAMOND).apply { close() }, tint, style = stroke)
                drawPath(path(P_LAYERS_V1), tint, style = stroke)
                drawPath(path(P_LAYERS_V2), tint, style = stroke)
            }

            // 缓存：圆柱（顶面椭圆 + 两条侧边 + 底弧）。顶面那圈用 `drawArc` 画**整圈**
            // （0→360、`useCenter=false`）：它比"画一个椭圆再擦掉下半"少一步，而
            // `drawOval` 不在这一格的风格里（本文件其它字形都走 path/arc 两条路）。
            // 底弧只画下半（0→180，顺时针从左到右经过下方），侧边因此只到 0.74 ——
            // 三条线在 24dp 上接得上，不会在同一点上叠出两个圆头。
            GlyphKind.CACHE -> {
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = at(0.17f, 0.13f),
                    size = Size(0.66f * u, 0.26f * u),
                    style = stroke,
                )
                drawPath(path(P_CACHE_SIDE_L), tint, style = stroke)
                drawPath(path(P_CACHE_SIDE_R), tint, style = stroke)
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = at(0.17f, 0.61f),
                    size = Size(0.66f * u, 0.26f * u),
                    style = stroke,
                )
            }
        }
    }
}

/**
 * 顶栏那颗「更多」（⋮）的画布边长：**全仓最粗的一档三点**。
 *
 * 与 [ROW_MORE_DOTS_SIZE] / [ROW_MORE_DOTS_WEIGHT] 那组一起，构成两档粗细
 * （用户口径：顶栏最粗、列表行尾细一点）。两处都是 `Glyph(GlyphKind.MORE_VERT, …)`
 * 加这组参数，**没有各自的包装函数** —— 一层的包装只是把同一个 `Glyph` 调用的实参
 * 藏起来，读的人反而要跳一层才知道粗细是多少。
 *
 * 实测尺寸：24dp 画布 × [BAR_MORE_DOTS_WEIGHT] → 点半径约 2.43dp（点径约 4.86dp、点距 7.4dp）；
 * 行尾那颗是点径 2.8dp —— 约 1.7 倍，这是**要求**不是随手调的：顶栏是整屏唯一的全局入口，
 * 行尾只是"这一行的操作"，两者重量必须读得出层级。层级由 `MoreDotsTest` 守着，
 * 免得下一个人顺手把两处改成一样。
 *
 * 为什么不是 `Text("⋮")`（本仓原先的写法）：那个字符的粗细由**系统字体**决定，
 * 换一台机器就是一个样，而且没法与行尾那颗比大小。画出来的点与行尾那颗**同形**。
 */
internal val BarMoreDotsSize = 24.dp

/** 顶栏三点的粗细倍率：最粗的一档（见 [BarMoreDotsSize]；2026-10-07 用户口径「粗细降低一点点」：1.3 → 1.15）。 */
internal const val BAR_MORE_DOTS_WEIGHT = 1.15f

/** 行尾三点的画布边长：比顶栏小一号（它住在 48dp 触控目标里，右边还要留 4dp 呼吸位）。 */
internal val RowMoreDotsSize = 20.dp

/** 行尾三点的粗细倍率：比顶栏细一档（见 [BarMoreDotsSize]）。 */
internal const val ROW_MORE_DOTS_WEIGHT = 0.8f
