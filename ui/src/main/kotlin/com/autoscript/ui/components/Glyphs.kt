package com.autoscript.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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

    /** 排序切换钮——切到**按字母**（三条横杠 + 字母 A；TG `msg_contacts_name`）。 */
    SORT_NAME,

    /** 排序切换钮——切到**按时间**（三条横杠 + 时钟；TG `msg_contacts_time`）。 */
    SORT_TIME,

    /** 搜索框放大镜（TG `FragmentSearchField` 用的 `outline_search_1_24`：粗环短柄）。 */
    SEARCH_FIELD,

    /** 展开/收起三角（TG `arrow_more`：CollapseTextCell 右侧的下尖 chevron）。 */
    CHEVRON,
}

/** 线宽 ÷ 图标边长。四个字形共用一条，粗细才不会一格一个样。 */
private const val StrokeRatio = 0.085f

/**
 * 画一个页签图标。
 *
 * @param tint 线条色（调用方给：选中 = 强调色，未选中 = 次级灰）。
 * @param weight 线宽倍率。**选中项略微加粗** —— 这套图标是线性的，没有"填充版"可切，
 *   加粗就是它这一档语言里的"实心"（TG 底栏选中项也是靠视觉重量拉开，不是靠颜色一条）。
 */
@Composable
fun Glyph(
    kind: GlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    weight: Float = 1f,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension
        val stroke = Stroke(
            width = u * StrokeRatio * weight,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun path(vararg points: Pair<Float, Float>): Path = Path().apply {
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u)
            }
        }

        when (kind) {
            // 房子：屋顶两笔 + 屋身三笔（左下→右下→右侧竖边）。不画门 ——
            // 24dp 里再塞一扇门就只剩一团黑。
            GlyphKind.HOME -> {
                drawPath(path(0.09f to 0.48f, 0.50f to 0.13f, 0.91f to 0.48f), tint, style = stroke)
                drawPath(
                    path(0.23f to 0.45f, 0.23f to 0.87f, 0.77f to 0.87f, 0.77f to 0.45f),
                    tint,
                    style = stroke,
                )
            }

            // 时钟：外圈 + 时针（朝上）+ 分针（朝右下）。任务是"什么时候跑"，钟面比清单更贴。
            GlyphKind.TASKS -> {
                drawCircle(tint, radius = 0.37f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(0.50f to 0.50f, 0.50f to 0.27f), tint, style = stroke)
                drawPath(path(0.50f to 0.50f, 0.69f to 0.61f), tint, style = stroke)
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
                drawPath(path(0.30f to 0.40f, 0.42f to 0.50f, 0.30f to 0.60f), tint, style = stroke)
                drawPath(path(0.54f to 0.60f, 0.71f to 0.60f), tint, style = stroke)
            }

            // 盾牌：上宽下尖的六边形。root/授权类条目管的是"开/关"，盾牌最不容易读错。
            GlyphKind.SHIELD -> {
                drawPath(
                    path(
                        0.50f to 0.11f,
                        0.87f to 0.25f,
                        0.87f to 0.51f,
                        0.50f to 0.89f,
                        0.13f to 0.51f,
                        0.13f to 0.25f,
                    ).apply { close() },
                    tint,
                    style = stroke,
                )
            }

            // 无障碍：头（圆）+ 躯干（一竖）+ 平举的双臂（一横）。
            GlyphKind.ACCESSIBILITY -> {
                drawCircle(tint, radius = 0.09f * u, center = at(0.5f, 0.16f), style = stroke)
                drawPath(path(0.20f to 0.34f, 0.80f to 0.34f), tint, style = stroke)
                drawPath(path(0.50f to 0.34f, 0.50f to 0.62f), tint, style = stroke)
                drawPath(path(0.50f to 0.62f, 0.36f to 0.88f), tint, style = stroke)
                drawPath(path(0.50f to 0.62f, 0.64f to 0.88f), tint, style = stroke)
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
                drawPath(path(0.50f to 0.70f, 0.50f to 0.82f), tint, style = stroke)
                drawPath(path(0.34f to 0.84f, 0.66f to 0.84f), tint, style = stroke)
            }

            // 悬浮窗：底层方块 + 右上叠出的小方块（层级感）。
            GlyphKind.OVERLAY -> {
                drawPath(
                    path(0.14f to 0.30f, 0.62f to 0.30f, 0.62f to 0.78f, 0.14f to 0.78f).apply { close() },
                    tint,
                    style = stroke,
                )
                drawPath(
                    path(0.38f to 0.22f, 0.86f to 0.22f, 0.86f to 0.70f),
                    tint,
                    style = stroke,
                )
            }

            // 设置：齿轮 = 外圈（圆）+ 齿（四向短刺）+ 中孔（小圆）。
            GlyphKind.SETTINGS -> {
                drawCircle(tint, radius = 0.30f * u, center = at(0.5f, 0.5f), style = stroke)
                drawCircle(tint, radius = 0.10f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(0.50f to 0.08f, 0.50f to 0.20f), tint, style = stroke)
                drawPath(path(0.50f to 0.80f, 0.50f to 0.92f), tint, style = stroke)
                drawPath(path(0.08f to 0.50f, 0.20f to 0.50f), tint, style = stroke)
                drawPath(path(0.80f to 0.50f, 0.92f to 0.50f), tint, style = stroke)
            }

            // 搜索：放大镜 = 斜柄 + 圆环（环心偏离几何中心，柄在右下）。
            GlyphKind.SEARCH -> {
                drawCircle(tint, radius = 0.28f * u, center = at(0.44f, 0.44f), style = stroke)
                drawPath(path(0.65f to 0.65f, 0.86f to 0.86f), tint, style = stroke)
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
                drawPath(path(0.42f to 0.40f, 0.30f to 0.50f, 0.42f to 0.60f), tint, style = stroke)
                drawPath(path(0.58f to 0.40f, 0.70f to 0.50f, 0.58f to 0.60f), tint, style = stroke)
            }

            // 文档：竖长页 + 右上折角 + 两行文字线。
            GlyphKind.FILE_DOC -> {
                drawPath(
                    path(0.24f to 0.10f, 0.62f to 0.10f, 0.76f to 0.26f, 0.76f to 0.90f, 0.24f to 0.90f).apply { close() },
                    tint,
                    style = stroke,
                )
                drawPath(path(0.62f to 0.10f, 0.62f to 0.26f, 0.76f to 0.26f), tint, style = stroke)
                drawPath(path(0.36f to 0.48f, 0.64f to 0.48f), tint, style = stroke)
                drawPath(path(0.36f to 0.62f, 0.64f to 0.62f), tint, style = stroke)
            }

            // 网页：地球 = 外圆 + 竖向椭圆（两条经线的近似）+ 赤道横线。
            GlyphKind.FILE_HTML -> {
                drawCircle(tint, radius = 0.38f * u, center = at(0.5f, 0.5f), style = stroke)
                drawPath(path(0.50f to 0.12f, 0.50f to 0.88f), tint, style = stroke)
                drawPath(path(0.24f to 0.28f, 0.76f to 0.28f), tint, style = stroke)
                drawPath(path(0.24f to 0.72f, 0.76f to 0.72f), tint, style = stroke)
                drawPath(path(0.12f to 0.50f, 0.88f to 0.50f), tint, style = stroke)
            }

            // Shell：终端提示符（与 CONSOLE 同形的简化版，少了外框 —— 文件图标要满框）。
            GlyphKind.FILE_SH -> {
                drawPath(path(0.18f to 0.34f, 0.36f to 0.50f, 0.18f to 0.66f), tint, style = stroke)
                drawPath(path(0.44f to 0.66f, 0.70f to 0.66f), tint, style = stroke)
            }

            // 其余：立方体 = 正面方块 + 右侧面（体量感）。
            GlyphKind.FILE_GENERIC -> {
                drawPath(
                    path(0.16f to 0.28f, 0.58f to 0.28f, 0.58f to 0.72f, 0.16f to 0.72f).apply { close() },
                    tint,
                    style = stroke,
                )
                drawPath(
                    path(0.58f to 0.28f, 0.84f to 0.42f, 0.84f to 0.86f, 0.58f to 0.72f),
                    tint,
                    style = stroke,
                )
            }

            // 通知：铃铛（钟形）+ 顶部小柄 + 底部小舌。
            GlyphKind.BELL -> {
                drawPath(
                    path(
                        0.50f to 0.14f,
                        0.22f to 0.42f,
                        0.22f to 0.68f,
                        0.78f to 0.68f,
                        0.78f to 0.42f,
                        0.50f to 0.14f,
                    ),
                    tint,
                    style = stroke,
                )
                drawPath(path(0.42f to 0.82f, 0.58f to 0.82f), tint, style = stroke)
            }

            // 文件夹：折角文件夹（TG menu_folder_add 的轮廓读法：背板 + 标签突起）。
            // 首尾闭合（close）—— 底边那一条横线不能少：开放路径在 24dp 下读起来就是
            // "没合上的文件夹"（批 25 实机截图定案的形状修正）。
            GlyphKind.FOLDER -> {
                drawPath(
                    path(
                        0.10f to 0.74f,
                        0.10f to 0.30f,
                        0.36f to 0.30f,
                        0.44f to 0.38f,
                        0.90f to 0.38f,
                        0.90f to 0.74f,
                    ).apply { close() },
                    tint,
                    style = stroke,
                )
            }

            // 铅笔（FAB 主钮）：斜杆 + 笔尖三角 + 笔杆横线（TG floating_pencil 的线性读法）。
            GlyphKind.PENCIL -> {
                drawPath(
                    path(
                        0.24f to 0.76f,
                        0.24f to 0.62f,
                        0.66f to 0.20f,
                        0.80f to 0.34f,
                        0.38f to 0.76f,
                    ),
                    tint,
                    style = stroke,
                )
                drawPath(path(0.24f to 0.76f, 0.38f to 0.76f, 0.24f to 0.62f), tint, style = stroke)
                drawPath(path(0.62f to 0.24f, 0.76f to 0.38f), tint, style = stroke)
            }

            // 播放三角（任务行尾「立即执行」）：实心（TG 这颗是填充形，线性描边读不出"按了会跑"）。
            GlyphKind.PLAY -> {
                drawPath(
                    path(0.30f to 0.20f, 0.30f to 0.80f, 0.80f to 0.50f).apply { close() },
                    tint,
                    style = Fill,
                )
            }

            // 排序切换（TG msg_contacts_name/time 的 webp 实测几何：三条圆头横杠
            // y≈0.23/0.45/0.67，长度递减 x→0.75/0.58/0.44；尾缀 = 切过去的那一档——
            // 按时间排序时显示字母 A（切到字母），按字母时显示时钟（切到时间），与 TG 同款）。
            GlyphKind.SORT_NAME -> {
                drawPath(path(0.11f to 0.23f, 0.75f to 0.23f), tint, style = stroke)
                drawPath(path(0.11f to 0.45f, 0.58f to 0.45f), tint, style = stroke)
                drawPath(path(0.11f to 0.67f, 0.44f to 0.67f), tint, style = stroke)
                drawPath(path(0.62f to 0.80f, 0.74f to 0.50f, 0.86f to 0.80f), tint, style = stroke)
                drawPath(path(0.655f to 0.70f, 0.825f to 0.70f), tint, style = stroke)
            }

            GlyphKind.SORT_TIME -> {
                drawPath(path(0.11f to 0.23f, 0.75f to 0.23f), tint, style = stroke)
                drawPath(path(0.11f to 0.45f, 0.58f to 0.45f), tint, style = stroke)
                drawPath(path(0.11f to 0.67f, 0.44f to 0.67f), tint, style = stroke)
                drawCircle(tint, radius = 0.165f * u, center = at(0.755f, 0.655f), style = stroke)
                drawPath(path(0.755f to 0.655f, 0.755f to 0.545f), tint, style = stroke)
                drawPath(path(0.755f to 0.655f, 0.83f to 0.655f), tint, style = stroke)
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
                drawPath(path(0.62f to 0.56f, 0.786f to 0.728f), tint, style = stroke)
            }

            // 展开/收起三角（arrow_more 的 webp 实测：下尖 chevron，三控制点
            // (0.19,0.36)→(0.50,0.70)→(0.81,0.36)，笔画 6/72≈0.083 ≈ StrokeRatio）。
            // 收起 = 尖朝下（原样），展开 = 转 180° 尖朝上（CollapseTextCell 的
            // `rotation(collapsed ? 0 : 180)` 同语义）。
            GlyphKind.CHEVRON -> {
                drawPath(path(0.19f to 0.36f, 0.50f to 0.70f, 0.81f to 0.36f), tint, style = stroke)
            }
        }
    }
}
