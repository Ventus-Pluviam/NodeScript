package com.autoscript.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.LocalEditorHighlightHost
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.color
import com.autoscript.ui.components.pressable
import com.autoscript.ui.state.EditorAffordance
import com.autoscript.ui.state.EditorHighlightSession
import com.autoscript.ui.state.EditorJump
import com.autoscript.ui.state.EditorScroll
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.SyntaxHighlightResult
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.ui.theme.isDarkTheme
import com.autoscript.ui.theme.syntaxColor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 脚本文本编辑器（项目页**点文件**进来的整屏编辑面；TG 文件页点开文档的对应位）。
 *
 * 版式：顶栏（‹ 返回 / 文件名 / 完整相对路径 / 右侧「保存」）+ 正文（等宽文本框）。
 * 保存按钮**只在有未保存改动时可点** —— 一颗永远可点的保存键会让人以为「点过就一定写进去了」。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 读取失败**不冒充空文件**：空文件与「没读到」在编辑框里必须长得不一样 ——
 *   前者可以直接写内容，后者写下去就是把原内容抹掉；
 * - 保存失败**原文留在正文上方**（不只用浮层提示）：浮层几秒后消失，
 *   而「这次没存上」这件事必须一直看得见，直到下一次保存或退出；
 * - 有未保存改动时返回**先问一句**（放弃 / 继续编辑）：静默丢弃用户刚敲的代码是最坏的一种「贴心」。
 *
 * 只负责编辑一个**已存在**的文件：新建/改名/删除都不在这里（新建归项目页 FAB，
 * 其余要宿主侧的口，没有的口不摆成按钮）。
 *
 * @param projectId 所属项目（`files/scripts/` 下第一级目录名）。
 * @param relPath 项目内相对路径（子文件夹里的文件靠它定位）。
 * @param displayName 顶栏标题（文件名，不含目录）。
 * @param onRead 读全文（挂起；失败原文抛）。
 * @param onSave 覆盖写（挂起；**正常返回即已落盘**，失败原文抛）。
 * @param onClose 退出编辑面（返回键与 ‹ 共用一条路径）。
 */
@Composable
fun ScriptEditorScreen(
    projectId: String,
    relPath: String,
    displayName: String,
    onRead: suspend (projectId: String, relPath: String) -> String,
    onSave: suspend (projectId: String, relPath: String, content: String) -> Unit,
    onClose: () -> Unit,
    /**
     * 把本屏那条「能不能退」的判定（含未保存拦截）交给外层。批 115：外层包了一层
     * [com.autoscript.ui.ManagementTransition]，**预测式手势的提交路径走的是它的
     * `onBackRequest`**，不经本屏那颗 `BackHandler` —— 于是「有未保存改动先问一句」
     * 那道门必须从这儿递出去，否则侧缘滑回来会**静默丢改动**。
     *
     * 用 `DisposableEffect` 注册：编辑器挂上树时把判定递出去，退出组合时收回。
     */
    onRegisterCloseGuard: ((() -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    // 保存成功后要收的两样东西：焦点（= 光标）与输入法（见下面保存那一段）。
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // **编辑面占整屏，底栏整条收掉**（用户口径）：编辑时那四个页签既点不到正文、又压着最后几行。
    // 开关由外壳供（[LocalTabBarHidden]），只在进出时写它 —— 退出（含配置重建）自动还回去。
    val tabBarHidden = LocalTabBarHidden.current
    DisposableEffect(Unit) {
        tabBarHidden.value = true
        onDispose { tabBarHidden.value = false }
    }
    // null = 还没读到（或正在读）：与「读到了但是空文件」（空串）必须区分开。
    //
    // 存的是 **TextFieldValue 而不是 String**：光标位置也是编辑器状态的一部分 ——
    // "点正文下方的空白把光标送到文末"（用户口径）要能**主动设选区**，而 String 那套重载
    // 的选区归 Compose 内部管，外面够不着。
    var value by remember { mutableStateOf<TextFieldValue?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 换文件（key 变）重读：编辑器是整屏面，同一个实例被复用到另一个文件上时必须把
    // 上一位的状态清干净，否则会拿着 A 的内容去存 B。
    LaunchedEffect(projectId, relPath) {
        value = null
        loadError = null
        saveError = null
        dirty = false
        val read = loadScriptText(projectId, relPath, onRead)
        // 读失败时正文是 null（不是空串），光标位置无从谈起 —— 两处一起置空。
        value = read.text?.let { TextFieldValue(it) }
        loadError = read.error
    }

    /** 退回上一层（返回键、‹、跟手手势三者共用）：有未保存改动先问一句，不静默丢。 */
    fun close() {
        if (dirty) confirmDiscard = true else onClose()
    }

    // **不再挂 `BackHandler`**：它是普通 `OnBackPressedCallback`，注册在外层的
    // `PredictiveBackHandler` 之后（`OnBackPressedDispatcher` 是 LIFO）—— 系统手势
    // 导航的**预测式返回会整个退化成普通返回**，跟手动画就不存在了。非手势返回键
    // 同样会触发 `PredictiveBackHandler` 的流结束（`handleOnBackPressed` → onCommit），
    // 所以整条返回路径都归外层那个 handler，这里只把「能不能退」递出去。
    //
    // 上一版还挂着它，且 `onClose` 链回外层 `requestEditorClose` → 再调回 `close()`
    // —— 没改动直接返回时会无限递归（栈溢出），表现就是「退出的时候直接软件都退出了」。
    DisposableEffect(Unit) {
        onRegisterCloseGuard(::close)
        onDispose { onRegisterCloseGuard(null) }
    }

    // 局部 val：`value` 是可变的，下面 when 的分支里要的是"读到了"那一档的非空值。
    val loaded = value
    Column(modifier.fillMaxSize().background(palette.background)) {
        ActionBar(
            title = displayName,
            subtitle = relPath,
            subtitleTone = StatusTone.MUTED,
            onBack = ::close,
            backGlyph = "‹",
            actions = {
                ActionBarAction(
                    text = if (saving) "保存中…" else "保存",
                    // 没改动 / 还没读到 / 正在存 → 不可点（见类 KDoc）。
                    enabled = loaded != null && dirty && !saving,
                    onClick = {
                        val current = loaded ?: return@ActionBarAction
                        scope.launch {
                            saving = true
                            saveError = null
                            saveError = saveScriptText(projectId, relPath, current.text, onSave)
                            saving = false
                            // 存失败：那句原文留在正文上方，**不收键盘**（还要接着改）。
                            if (saveError != null) return@launch
                            dirty = false
                            // 存完就**收键盘、撤光标**（用户口径）：写完了还杵着输入法 +
                            // 一根闪烁光标，读起来像"还在编辑、没存上"。清焦点即撤光标
                            // （未聚焦的 BasicTextField 不画光标），键盘随之收起。
                            focusManager.clearFocus()
                            keyboard?.hide()
                            toast?.show("已保存「$displayName」")
                        }
                    },
                )
            },
        )
        // 保存失败的原文一直留在正文上方（浮层会消失，这句不会）。
        saveError?.let { reason ->
            Text(
                text = "保存失败：$reason",
                color = StatusTone.PROBLEM.color(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            loadError != null -> EditorNotice(text = "读不到这个文件：$loadError")
            loaded == null -> EditorNotice(text = "读取中…")
            // 换文件时编辑器会**整块重建**（重读期间这一支不在树上）：滚动位置、光标、
            // 手指方向都不跨文件继承 —— 不需要再给它们加 key。
            else -> EditorBody(
                value = loaded,
                relPath = relPath,
                onValueChange = { next ->
                    // **只有正文真的变了才标脏**：点正文下方的空白只是把光标挪到文末
                    // （用户口径），正文一个字没动 —— 那时亮起"保存"、退出还要问
                    // "有未保存的修改"，就是在骗人。
                    val edited = next.text != loaded.text
                    value = next
                    if (edited) {
                        dirty = true
                        // 一改动就把上一次的保存失败结论收掉：那句话说的是**上一次**的
                        // 内容，留在屏幕上会让人以为这次也没存上。
                        saveError = null
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 底栏留白：编辑器住页签条**下面**那一层（外壳画的底栏浮在页内容之上），
                    // 不留白就会被胶囊底栏压住最后几行（编辑态底栏已收起，这里只剩导航栏）。
                    .padding(bottom = TabBarBottomClearance()),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("有未保存的修改") },
            text = { Text("退出会丢掉这些改动。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) {
                    Text("放弃修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("继续编辑")
                }
            },
        )
    }
}

/**
 * 读一次正文：**读到了给正文，读不到给失败原文**，两者绝不会同时非空。
 *
 * 单独抽出来的两个理由：① 「失败不冒充空文件」这条判据现在是一个可以直接单测的函数
 * （空文件 = `text = ""`、读失败 = `text = null` + `error`），而不是埋在组合里的
 * try/catch；② 组合函数已经贴着 detekt 的长函数线。
 *
 * 取消**照原样抛**（`CancellationException` 是协程的通行证，吞了会让换文件时的重读
 * 变成"上一位的结果盖到新文件上"）。
 */
internal suspend fun loadScriptText(
    projectId: String,
    relPath: String,
    onRead: suspend (projectId: String, relPath: String) -> String,
): ScriptLoad = try {
    ScriptLoad(text = onRead(projectId, relPath), error = null)
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    ScriptLoad(text = null, error = t.message ?: t.javaClass.simpleName)
}

/**
 * 保存一次：**正常返回即已落盘**；失败把原文作为结果带回来（不往组合里抛）。
 *
 * 与 [loadScriptText] 同一条纪律：`CancellationException` 照原样抛（协程的通行证），
 * 其余异常都收成"这次没存上"的那句话 —— 由调用方决定它显示在哪儿。
 */
internal suspend fun saveScriptText(
    projectId: String,
    relPath: String,
    content: String,
    onSave: suspend (projectId: String, relPath: String, content: String) -> Unit,
): String? = try {
    onSave(projectId, relPath, content)
    null
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    t.message ?: t.javaClass.simpleName
}

/**
 * [loadScriptText] 的结果。
 *
 * @property text 正文；`null` = 没读到（**不是**空文件 —— 空文件是 `""`）。
 * @property error 失败原文；`null` = 读到了。
 */
internal data class ScriptLoad(val text: String?, val error: String?)

/**
 * 正文那一层：**行号槽 + 正文 + 右下那颗跳转钮**（含它上面那个数）。
 *
 * 正文**不折行**（用户口径）：一行就是一行，超出的部分交给横向滚动，所以"第几行"
 * 只有一个口径 —— 行号列就是 `1..lineCount(text)`，与正文天然逐行对齐。
 * 双指捏合改的是**字号/行高/字距**（见 [detectPinchZoom]），不是把画面拉伸。
 *
 * **捏合期间不重排**（用户口径"有点卡"的修法）：字号是排版的输入，改它就得把整篇重排一遍 ——
 * 文档几百行时每帧重排一次就是每帧掉帧。这里把捏合拆成两段：手势期间只改 [EditorZoom] 的
 * 预览字段（三个数都**只在绘制阶段**被读，改它们不触发重组、更不重排，画面直接跟着手指走）；
 * 松手时提交成真字号（一次重排），并把这期间"欠"的滚动补上（见 [commitPinch]）。
 *
 * 单独一个函数有两个理由：① [ScriptEditorScreen] 已经贴着 detekt 的长函数线；
 * ② 它自带几份状态（纵横两根滚动轴、缩放、待补滚动、排版高度、手指方向）—— 留在屏里
 * 会和保存/退出那套混在一起。
 *
 * @param value 正文与**光标位置**（选区由这一层改，见"点空白送到文末"那一段）。
 * @param onValueChange 内容或选区变了（调用方据此标脏、清掉上一次的保存失败结论）。
 */
@Composable
private fun EditorBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    relPath: String,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // 双指缩放的**已提交倍率**与手势预览（见 [EditorZoom]）：字号/行高/字距整组乘已提交倍率。
    // 不是 `graphicsLayer` 拉伸画面 —— 拉伸只改画面、不改排版，滚动范围、行号位置、
    // "屏幕外还有几行"会全部对不上；改字号是让排版自己重算一遍，所有几何都跟着走。
    val zoom = remember { EditorZoom() }
    // 提交缩放后要补的那一次滚动：算的时候新排版还没跑（`maxValue` 还是旧的），
    // 所以等下一帧排版落地再落它 —— 在那之前画面靠 `zoom.shift` 钉住。
    var pendingScroll by remember { mutableStateOf<IntOffset?>(null) }
    // 正文自己占的高度（**不折行**之后 = 行数 × 行高）：判断"点在不在正文下方"要用它。
    // 存 Float 而不是 TextLayoutResult：等值比较，布局回调万一再来一次也不会自激。
    // 它的读者**只有**手势闭包（不是组合），所以布局期写它不会再触发一轮布局。
    var contentHeightPx by remember { mutableStateOf(0f) }
    // 手指方向：往下拖 = 往上看（见 [EditorScroll.affordance]）。默认 false = 去最底。
    var towardTop by remember { mutableStateOf(false) }
    ScrollDirectionEffect(vScroll) { towardTop = it }
    PendingScrollEffect(pendingScroll, vScroll, hScroll, zoom) { pendingScroll = null }
    // 正文与行号槽**共用同一套字体度量**（同一个 TextStyle，只换对齐与颜色）：换字号、
    // 换行高、换字距都只改一处，否则第 n 个数字会逐行偏离第 n 行。
    val highlighted = rememberEditorHighlight(value.text, relPath)
    val codeStyle = remember(palette.text, zoom.committed) {
        editorCodeStyle(palette.text, zoom.committed)
    }
    val gutterStyle = remember(codeStyle, palette.textTertiary) {
        editorGutterStyle(codeStyle, palette.textTertiary)
    }
    // 手势闭包（`pointerInput(Unit)` 只捕获**第一次**组合的那些值）里要读的东西一律走
    // `rememberUpdatedState`：不然换了文件、改了字号之后，点击判据与几何还是上一版的。
    val currentValue = rememberUpdatedState(value)
    val currentOnChange = rememberUpdatedState(onValueChange)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = editorMetrics(
            viewportWidth = maxWidth,
            viewportHeight = maxHeight,
            density = density,
            zoom = zoom.committed,
            lines = EditorScroll.lineCount(value.text),
        )
        val gutterText = remember(metrics.lines) { (1..metrics.lines).joinToString("\n") }
        // 手势要用的几何（内容坐标 px）：打包成一个值再走 `rememberUpdatedState` ——
        // 上面那些 `val` 每次组合都新算一遍，而 `pointerInput(Unit)` 的闭包只认第一次捕获的。
        val currentMetrics = rememberUpdatedState(metrics)
        // 那个数与按钮去哪一头：**只在"跨过一行"或"方向翻面"时才变值** ——
        // 直接读 `vScroll.value` 会让整块编辑面每滚一帧重组一次。
        val affordance by remember(metrics.viewportPx, metrics.lineHeightPx, metrics.lines) {
            derivedStateOf {
                // `vScroll.value` 是 Int（px 整数），几何那层一律吃 Float。
                EditorScroll.affordance(
                    vScroll.value.toFloat(),
                    metrics.viewportPx,
                    metrics.lineHeightPx,
                    metrics.lines,
                    towardTop,
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                // 捏合挂在**滚动容器**这一层，不是正文那一行：① 它必须是预览层
                // （下面那个 `graphicsLayer`）的**祖先** —— 命中测试会按缩放做逆变换，
                // 挂在里面的话手指间距会被自己缩掉，越捏越偏；② 这一层的坐标已经是
                // **内容坐标**（滚动位移算进去了），"手指底下那个点"不用再换算。
                .pinchZoom(
                    zoom = zoom,
                    metrics = { currentMetrics.value },
                    vScroll = vScroll,
                    hScroll = hScroll,
                    density = density,
                    onScroll = { pendingScroll = it },
                ),
        ) {
            Row(
                Modifier
                    // 捏合预览层（见 [EditorZoom]）：三个数都在绘制阶段读，捏合期间不重组、不重排。
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        // 锚点 = 两指中点（内容坐标）：缩放围着它做，手指底下的字不动。
                        transformOrigin = TransformOrigin(
                            if (size.width > 0f) zoom.anchor.x / size.width else 0.5f,
                            if (size.height > 0f) zoom.anchor.y / size.height else 0.5f,
                        )
                        translationX = zoom.shift.x
                        translationY = zoom.shift.y
                    }
                    .fillMaxWidth()
                    // 正文不足一屏时，下面那一块**也要收得到点击**（用户口径：点空白把光标
                    // 送到文末）。不给最小高度，那块空白根本不属于编辑器，点了没反应。
                    .heightIn(min = metrics.viewportHeight)
                    // 手势挂在**内边距之外**这一层：`at` 的坐标就是整行的坐标，
                    // "在不在正文下方"只需和正文高度比，不用再去减内边距。
                    .tapToTextEnd(
                        metrics = { currentMetrics.value },
                        textHeightPx = { contentHeightPx },
                        value = { currentValue.value },
                        onValueChange = { currentOnChange.value(it) },
                        focusRequester = focusRequester,
                    )
                    // 下方留出那颗圆钮的位置：滚到最底时最后一行正好停在钮上方，
                    // 而不是被它压住半行（钮是浮层，不参与布局，只能这样让位）。
                    .padding(top = EditorVerticalPadding, bottom = EditorChipReserve),
                verticalAlignment = Alignment.Top,
            ) {
                EditorGutter(
                    width = metrics.gutterWidth,
                    text = gutterText,
                    style = gutterStyle,
                )
                EditorTextArea(
                    value = value,
                    onValueChange = onValueChange,
                    highlightedText = highlighted,
                    hScroll = hScroll,
                    focusRequester = focusRequester,
                    minWidth = metrics.textViewportWidth,
                    textStyle = codeStyle,
                    onHeight = { contentHeightPx = it },
                )
            }
        }
        EditorJumpChip(
            affordance = affordance,
            // 那个数只在正文**超出一屏**时出现：没超出时"屏幕外还有几行"是个假问题。
            // 比的是"正文能不能占满视口"（上下留白不算正文，要从视口里扣掉）。
            showBadge = EditorScroll.overflows(
                metrics.lines,
                metrics.lineHeightPx,
                metrics.viewportPx - metrics.contentPaddingPx,
            ),
            onJump = {
                val toTop = affordance.jump == EditorJump.TOP
                scope.launch {
                    vScroll.animateScrollTo(if (toTop) 0 else vScroll.maxValue)
                    // 跳完**把方向翻过来**：刚去了顶部，下一次有用的是"去底部"。不翻的话
                    // 钮会停在"去顶部"上、再点一次什么都不发生 —— 看起来像按钮坏了。
                    // （用户口径只规定了"手指方向 → 钮的方向"，跳转后的方向由这里补齐。）
                    towardTop = !toTop
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(EditorChipMargin),
        )
    }
}

/**
 * 跟着滚动位置更新"手指往哪边拖"（见 [EditorScroll.affordance]）。**只读变化量、不读位置** ——
 * 直接读 `scroll.value` 会让整块编辑面每滚一帧重组一次。
 *
 * @param onDirection 刚往上滚给 `true`（"你在往上看"）、刚往下滚给 `false`。
 */
@Composable
private fun ScrollDirectionEffect(scroll: ScrollState, onDirection: (Boolean) -> Unit) {
    // `LaunchedEffect(scroll)` 的闭包只认**第一次**组合的那个 lambda：包一层拿当下的。
    val current = rememberUpdatedState(onDirection)
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { now ->
            if (now != last) {
                current.value(now < last)
                last = now
            }
        }
    }
}

/**
 * 提交缩放后要补的那次滚动（见 [commitPinch]）：算的时候新排版还没跑（`maxValue` 还是旧的），
 * 所以要等下一帧排版落地再落它 —— 在那之前画面靠 [EditorZoom.shift] 钉住。
 *
 * 真滚动与撤掉补偿位移**同一批**写，而且走 `dispatchRawDelta`（同步、不挂起）：错开一帧会看见
 * 画面弹一下，挂起则可能被下一次捏合取消在"滚到一半、位移还在"的中间态。
 */
@Composable
private fun PendingScrollEffect(
    pending: IntOffset?,
    vScroll: ScrollState,
    hScroll: ScrollState,
    zoom: EditorZoom,
    onApplied: () -> Unit,
) {
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        vScroll.dispatchRawDelta((pending.y - vScroll.value).toFloat())
        hScroll.dispatchRawDelta((pending.x - hScroll.value).toFloat())
        zoom.shift = Offset.Zero
        onApplied()
    }
}

/**
 * 编辑器的**缩放状态**：已提交倍率 + 捏合中的预览。
 *
 * 为什么合成一个对象：这两半是一件事 —— 手势期间只动 `scale/anchor/shift`（**只在绘制阶段**
 * 被读，不重组不重排），松手时 `committed` 换值（触发一次重排）而 `scale` 归位。拆成四个
 * 局部 `var` 传进 [Modifier.pinchZoom] 会撞 detekt 的参数上限，也读不出"这是一件事"。
 */
private class EditorZoom {
    /** 已提交的倍率：字号/行高/字距/行号槽宽**整组**乘它。 */
    var committed by mutableStateOf(1f)

    /** 捏合中的画面倍率（相对 [committed]）：只在 `graphicsLayer` 的闭包里被读。 */
    var scale by mutableFloatStateOf(1f)

    /** 缩放锚点（**内容坐标** px）：两指中点，缩放围着它做。 */
    var anchor by mutableStateOf(Offset.Zero)

    /** 提交那一帧的补偿位移：新字号 + 老滚动 vs 新滚动之间的差，真滚动落下后清零。 */
    var shift by mutableStateOf(Offset.Zero)
}

/**
 * 编辑器这一刻的**几何**（视口、行高、留白、行号槽宽）。
 *
 * 为什么要打包：这些数由"视口尺寸 + 缩放 + 行数"推出来，而手势闭包（`pointerInput(Unit)`
 * 只捕获第一次组合的值）要的是**当下**那一份 —— 打包成一个值才走得通 `rememberUpdatedState`。
 * dp 与 px 两套都留着：布局吃 dp，手势/绘制吃 px（换算只在这一处做）。
 */
private data class EditorMetrics(
    val viewportHeight: Dp,
    val viewportPx: Float,
    val lineHeightPx: Float,
    val textTopPx: Float,
    val contentPaddingPx: Float,
    val textStartPx: Float,
    val gutterWidth: Dp,
    val gutterWidthPx: Float,
    val textViewportWidth: Dp,
    val lines: Int,
    val digits: Int,
)

/** 由视口尺寸、缩放倍率与行数算出这一刻的全部几何（换算是唯一入口）。 */
private fun editorMetrics(
    viewportWidth: Dp,
    viewportHeight: Dp,
    density: Density,
    zoom: Float,
    lines: Int,
): EditorMetrics {
    val digits = EditorScroll.gutterDigits(lines)
    // 槽宽按行数留（最大行号是几位就留几位）；数字跟着缩放，槽宽也得跟着。
    val gutterWidth = EditorGutterStart + EditorGutterDigit * zoom * digits + EditorGutterEnd
    return EditorMetrics(
        viewportHeight = viewportHeight,
        viewportPx = with(density) { viewportHeight.toPx() },
        // 行高跟着缩放走：那个数与"超没超出一屏"都按**当前**行高算。
        lineHeightPx = with(density) { (EditorLineHeight * zoom).toPx() },
        // 正文上沿到整行上沿的距离（判断"点的是不是正文下方"时要用）。
        textTopPx = with(density) { EditorVerticalPadding.toPx() },
        // 正文上下那两块留白（上 12dp + 下 60dp 让位给圆钮）：判断"超没超出一屏"时
        // 它们是内容的一部分，要从视口里扣掉。
        contentPaddingPx = with(density) { (EditorVerticalPadding + EditorChipReserve).toPx() },
        textStartPx = with(density) { EditorTextStart.toPx() },
        gutterWidth = gutterWidth,
        gutterWidthPx = with(density) { gutterWidth.toPx() },
        // 正文可视宽度（Row 里扣掉行号槽那一块）：正文不足一屏宽时也要占满它，
        // 否则右边那片空白点不到、也落不了光标。
        textViewportWidth = viewportWidth - gutterWidth,
        lines = lines,
        digits = digits,
    )
}

/**
 * 双指捏合缩放：手势期间只改 [EditorZoom] 的预览字段（画面跟着手指走、一个字都不重排），
 * 松手时提交成真字号并补一次滚动（见 [commitPinch]）。
 *
 * @param metrics 取**当下**几何（`rememberUpdatedState` 包着的那一份，不是第一次组合的）。
 * @param onScroll 提交后要补的那次滚动（由调用方在新排版落地之后执行）。
 */
private fun Modifier.pinchZoom(
    zoom: EditorZoom,
    metrics: () -> EditorMetrics,
    vScroll: ScrollState,
    hScroll: ScrollState,
    density: Density,
    onScroll: (IntOffset) -> Unit,
): Modifier = pointerInput(Unit) {
    var base = 1f
    var factor = 1f
    var anchor = Offset.Zero
    detectPinchZoom(
        onStart = { at ->
            base = zoom.committed
            factor = 1f
            anchor = at
            zoom.anchor = at
            zoom.scale = 1f
            zoom.shift = Offset.Zero
        },
        onZoom = { cumulative ->
            factor = cumulative
            // 预览倍率 = 目标倍率 ÷ 已提交倍率：画面上看到的就是目标倍率。
            zoom.scale = (base * cumulative).coerceIn(EDITOR_ZOOM_MIN, EDITOR_ZOOM_MAX) /
                zoom.committed
        },
        onEnd = {
            val target = (base * factor).coerceIn(EDITOR_ZOOM_MIN, EDITOR_ZOOM_MAX)
            zoom.scale = 1f
            if (target != zoom.committed) {
                onScroll(commitPinch(target, anchor, zoom, metrics(), vScroll, hScroll, density))
                zoom.committed = target
            }
        },
    )
}

/**
 * 把一次捏合**提交**成真的缩放：算出两根轴要滚到哪，并先用补偿位移把画面钉住。
 *
 * 返回的是**待补的那次滚动** —— 提交这一刻 `maxValue` 还是老排版的，直接滚会被夹在旧上界上。
 * 在那之前 `zoom.shift` 让"新字号 + 老滚动"这一帧看起来跟提交前一模一样，于是松手时画面不跳。
 *
 * 两根轴的不动点不一样：垂直是正文**上留白**（首行上沿），水平是正文**左内边距** —— 这两截是
 * dp 内边距，不随字号缩放；其余内容相对它们线性伸缩，所以滚动值按同一把尺子换算。
 */
private fun commitPinch(
    target: Float,
    anchor: Offset,
    zoom: EditorZoom,
    metrics: EditorMetrics,
    vScroll: ScrollState,
    hScroll: ScrollState,
    density: Density,
): IntOffset {
    val ratio = target / zoom.committed
    val scrollY = EditorScroll.anchoredScroll(
        oldScrollPx = vScroll.value,
        anchorViewportPx = anchor.y - vScroll.value,
        fixedPx = metrics.textTopPx,
        ratio = ratio,
    )
    // 水平除了正文那一层缩放，行号槽本身也随字号变宽/变窄，差出来的那截要补给滚动值。
    val gutterAfterPx = with(density) {
        (EditorGutterStart + EditorGutterDigit * target * metrics.digits + EditorGutterEnd).toPx()
    }
    val scrollX = if (anchor.x <= metrics.gutterWidthPx) {
        hScroll.value
    } else {
        EditorScroll.anchoredScroll(
            oldScrollPx = hScroll.value,
            anchorViewportPx = anchor.x - metrics.gutterWidthPx,
            fixedPx = metrics.textStartPx,
            ratio = ratio,
        ) + (gutterAfterPx - metrics.gutterWidthPx).roundToInt()
    }
    zoom.shift = Offset(
        x = (hScroll.value - scrollX).toFloat(),
        y = (vScroll.value - scrollY).toFloat(),
    )
    return IntOffset(scrollX, scrollY)
}

/**
 * 点正文下方的空白 → 光标送到文末（用户口径）。
 *
 * 只认**正文下方、且不在行号槽里**的空白：正文本身有自己的落光标逻辑（点哪落哪），
 * 行号槽点了不该动光标。三个取值都走 lambda，读的是 `rememberUpdatedState` 里的当下值。
 */
private fun Modifier.tapToTextEnd(
    metrics: () -> EditorMetrics,
    textHeightPx: () -> Float,
    value: () -> TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
): Modifier = pointerInput(Unit) {
    detectTapGestures { at ->
        val geometry = metrics()
        if (at.y > textHeightPx() + geometry.textTopPx && at.x > geometry.gutterWidthPx) {
            val current = value()
            // `composition = null`：正在输入法组词时改选区要先收掉那一段组词，
            // 否则光标会跳回组词开头。
            onValueChange(
                current.copy(
                    selection = TextRange(current.text.length),
                    composition = null,
                ),
            )
            focusRequester.requestFocus()
        }
    }
}

/**
 * 行号槽：一条淡灰竖线把它和正文分开（用户口径）。数字与正文**同一套行高**（同一个 TextStyle
 * 派生的），所以第 n 个数字恰好落在第 n 行上。
 */
@Composable
private fun EditorGutter(width: Dp, text: String, style: TextStyle) {
    // 颜色在组合里取好再进绘制闭包：`ThemeColors` 是 @Composable 读口，绘制/手势闭包里取不到。
    val dividerColor = ThemeColors.divider
    Box(
        Modifier
            .width(width)
            .drawBehind {
                // 竖线画在槽的**右缘**：贴着正文那一侧，不占正文的宽度。
                val lineWidth = EditorGutterLineWidth.toPx()
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(size.width - lineWidth, 0f),
                    size = Size(lineWidth, size.height),
                )
            },
    ) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.fillMaxWidth().padding(end = EditorGutterEnd),
        )
    }
}

/**
 * 正文：**不折行 + 横向滚动**（用户口径）。一行就是一行，长行往右拉；于是"第几行"只有一个
 * 口径，行号列与正文天然逐行对齐（折行那套 `getLineForOffset` 映射整个不需要了）。
 */
@Composable
private fun RowScope.EditorTextArea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlightedText: AnnotatedString?,
    hScroll: ScrollState,
    focusRequester: FocusRequester,
    minWidth: Dp,
    textStyle: TextStyle,
    onHeight: (Float) -> Unit,
) {
    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = { original ->
                if (highlightedText != null) {
                    TransformedText(highlightedText, OffsetMapping.Identity)
                } else {
                    TransformedText(original, OffsetMapping.Identity)
                }
            },
            modifier = Modifier
                // **不折行**靠的是约束而不是参数：`BasicTextField` 没有 `softWrap`，但外层
                // `horizontalScroll` 会给内容无限宽 —— 拿不到宽度上限，排版就不会折行，
                // 字段宽度跟着最长的行走。`widthIn(min = …)` 补另一头：正文比一屏窄时也占满
                // 可视宽度，右边那片空白才点得到、才落得了光标。
                .widthIn(min = minWidth)
                .focusRequester(focusRequester)
                .padding(start = EditorTextStart, end = EditorTextEnd),
            textStyle = textStyle,
            cursorBrush = SolidColor(ThemeColors.accent),
            onTextLayout = { layout -> onHeight(layout.size.height.toFloat()) },
        )
    }
}

/**
 * 双指捏合：把**累计倍率**（相对手势开始那一刻的两指距离）报给调用方。
 *
 * 为什么是累计而不是逐次：逐次相乘会让浮点误差一路攒着（捏二十下之后倍率会漂），而且调用方
 * 也没法从"这一次的增量"还原出"我现在捏到哪了"。
 *
 * 为什么不用 `detectTransformGestures`：它**单指**拖动过了 touch slop 之后也会 `consume()`
 * —— 竖向滚动、"点空白落光标"、长按选词当场全失效。这里等到第二根手指落下才开始算两指距离，
 * 单指阶段一个事件都不碰。
 *
 * 又为什么跑在 [PointerEventPass.Initial] 上：Initial 趟是**父 → 子**，而横向滚动与文本框
 * 自己的手势都在子树里（Main 趟是子 → 父，抢不过它们）。两根手指往两边分开，在横向滚动看来
 * 就是一次"横向拖动"—— 不在这趟里拦下来，缩放会跟横向滚动打架。
 *
 * @param onStart 第二根手指落下：给两指中点（**内容坐标**，调用方拿它当缩放锚点）。
 * @param onZoom 累计倍率（当前两指距离 ÷ 手势开始时的距离）。
 * @param onEnd 捏合结束（抬起一根或全部抬起）；捏过就一定会在结束路径上被调一次。
 */
private suspend fun PointerInputScope.detectPinchZoom(
    onStart: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDistance = 0f
        var started = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            // 不足两根手指：要么还没开始捏（接着等），要么捏到一半抬起一根（收工）。
            if (pressed.size < 2) {
                if (started) onEnd()
                if (started || pressed.isEmpty()) return@awaitEachGesture
                continue
            }
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (started) {
                if (startDistance > 0f) onZoom(distance / startDistance)
            } else {
                started = true
                startDistance = distance
                onStart((pressed[0].position + pressed[1].position) / 2f)
            }
            // **在 Initial 趟消费**：子树（横向滚动 / 文本框）就收不到这两个指针了。
            pressed.forEach { it.consume() }
        }
    }
}

/**
 * 编辑器右下那颗圆钮与它上面那个数。
 *
 * 形态按用户口径：**正圆、白底、一圈淡灰描边**；钮上是**直角**折线（两条边成 90°，长短与
 * 粗细是 [EditorChipGlyphLeg] / [EditorChipGlyphStroke] 两个数），往下 = 去最底、往上 = 去最顶。
 *
 * 那个数（屏幕外还剩几行）的**中心压在圆的边线上**（见 [straddleTopEdge]），而且是这颗钮的
 * **子节点** —— 于是它既骑在按钮上，又不会抢走按下去的手势（子节点没有自己的 pointerInput，
 * 事件照旧归按钮）。
 *
 * @param affordance 去哪一头 + 那个数。
 * @param showBadge 正文有没有超出一屏（没超出就不摆那个数）。
 */
@Composable
private fun EditorJumpChip(
    affordance: EditorAffordance,
    showBadge: Boolean,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(EditorChipSize)
            .background(ThemeColors.editorChipBackground, CircleShape)
            .border(EditorChipBorderWidth, ThemeColors.editorChipBorder, CircleShape)
            .pressable(role = Role.Button, shape = CircleShape, onClick = onJump),
        contentAlignment = Alignment.Center,
    ) {
        JumpChevron(towardTop = affordance.jump == EditorJump.TOP)
        AnimatedVisibility(
            visible = showBadge,
            enter = fadeIn(tween(EDITOR_CHIP_FADE_MILLIS)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(EDITOR_CHIP_FADE_MILLIS)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).straddleTopEdge(),
        ) {
            LineCountBadge(count = affordance.offscreenLines)
        }
    }
}

/**
 * 钮上那个**直角**箭头：两条边成 90°，顶点朝下（去最底）或朝上（去最顶）。
 *
 * 为什么是画出来的折线而不是 `↓`/`↑` 字形：用户口径点名要"直角向下/向上"，而字形给不出
 * 可调的边长与线宽 —— 画布可以（[EditorChipGlyphLeg] / [EditorChipGlyphStroke] 就是那两处）。
 */
@Composable
private fun JumpChevron(towardTop: Boolean) {
    // 同上：颜色在组合里取好，画布里读不到 @Composable 的 `ThemeColors`。
    val color = ThemeColors.editorChipIcon
    Canvas(Modifier.size(EditorChipGlyph)) {
        val stroke = EditorChipGlyphStroke.toPx()
        val leg = EditorChipGlyphLeg.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 顶点朝向：+1 = 向下（去最底），-1 = 向上（去最顶）。
        val dir = if (towardTop) -1f else 1f
        val tip = Offset(cx, cy + dir * leg / 2f)
        val back = cy - dir * leg / 2f
        // 两条边各与竖直方向成 45°：夹角正好 90°（"直角"），横向半宽 = 纵向高度 = leg。
        drawLine(color, tip, Offset(cx - leg, back), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, tip, Offset(cx + leg, back), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/**
 * 那个数：一位一个"滚轮"。
 *
 * 口径来自用户：**数变小的时候往上滚、变大往下滚**（像机械计数器拨轮），而且**一位一位**
 * 地滚（每位差 [EDITOR_ROLL_STAGGER] 毫秒，从高位到低位依次跟上）。等宽字体是必须的 ——
 * 比例数字每一位宽度不同，滚起来整块会左右抖。
 */
@Composable
private fun LineCountBadge(count: Int) {
    Row(
        Modifier
            .background(ThemeColors.editorChipBackground, RoundedCornerShape(EditorBubbleRadius))
            .border(
                EditorChipBorderWidth,
                ThemeColors.editorChipBorder,
                RoundedCornerShape(EditorBubbleRadius),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        count.toString().forEachIndexed { index, digit ->
            RollingDigit(digit = digit, index = index)
        }
    }
}

/**
 * 一位数字的滚轮：新数字从哪边进来由**数值方向**决定。
 *
 * 变小 → 新数字从**下面**升上来（往上滚）；变大 → 从上面落下来。判据直接用字符本身
 * （都是 `0`..`9`，比大小就是比数值）。
 */
@Composable
private fun RollingDigit(digit: Char, index: Int) {
    AnimatedContent(
        targetState = digit,
        transitionSpec = {
            val rollingUp = targetState < initialState
            val delay = index * EDITOR_ROLL_STAGGER
            val slide = tween<IntOffset>(EDITOR_ROLL_MILLIS, delayMillis = delay)
            val fade = tween<Float>(EDITOR_ROLL_MILLIS, delayMillis = delay)
            val enter = slideInVertically(animationSpec = slide) { height ->
                if (rollingUp) height else -height
            }
            val exit = slideOutVertically(animationSpec = slide) { height ->
                if (rollingUp) -height else height
            }
            (enter + fadeIn(fade)) togetherWith (exit + fadeOut(fade))
        },
        label = "editorLineCountDigit",
    ) { shown ->
        Text(text = shown.toString(), color = ThemeColors.editorChipIcon, style = EditorBadgeStyle)
    }
}

/**
 * 把内容**骑在父级的上边线上**：中心落在 `y = 0`（自己往上探出半个高度），尺寸照旧。
 *
 * 这是"数字图标中心点在圆形边线上"的实现方式 —— 用布局位移而不是写死一个偏移量：那个数
 * 是一位还是两位、字体行高将来改了，中心都还在边线上。
 */
private fun Modifier.straddleTopEdge(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(x = 0, y = -placeable.height / 2)
    }
}

/** 那个数用的字：**等宽**是必须的 —— 比例数字每位宽度不同，滚轮会左右抖。 */
private val EditorBadgeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 14.sp,
)

/** 钮上那个直角箭头的画布边长（箭头本身比它小一圈）。 */
private val EditorChipGlyph = 24.dp

/** 直角箭头的**边长**：横向半宽 = 纵向高度 = 它，两条边各 45°，夹角才是 90°。 */
private val EditorChipGlyphLeg = 7.dp

/** 直角箭头的线宽。 */
private val EditorChipGlyphStroke = 2.5.dp

/** 滚轮一位数字的时长与相邻两位的错开量（"一位一位滚"就是靠这个错开量）。 */
private const val EDITOR_ROLL_MILLIS = 220
private const val EDITOR_ROLL_STAGGER = 40


/**
 * 正文与行号槽**共用**的那套字体度量（行号只换对齐与颜色）。
 *
 * 双指缩放（[zoom]）乘在字号/行高/字距三处：只改这一处，行号槽与正文的对齐关系
 * 才不会散（改字号不改行高、或只改正文不改行号，第 n 个数字就会逐行偏离第 n 行）。
 */
private fun editorCodeStyle(color: Color, zoom: Float): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = EditorFontSize * zoom,
    lineHeight = EditorLineHeight * zoom,
    letterSpacing = EditorLetterSpacing * zoom,
    color = color,
)

/** 行号槽的样式：与正文同一套度量，只换右对齐与更淡的颜色。 */
private fun editorGutterStyle(code: TextStyle, color: Color): TextStyle =
    code.copy(textAlign = TextAlign.End, color = color)

/** 正文字号（等宽 14sp，与重构前一致）。双指缩放的倍率乘在它（以及行高、字距）上。 */
private val EditorFontSize = 14.sp

/** 正文行高：行号槽、气泡那个数、点空白的判据**全按它算** —— 改它要三处一起看。 */
private val EditorLineHeight = 20.sp

/** 字间距（用户口径：别让字连在一起）。等宽字体的 CJK 与数字挤在一起最难认。 */
private val EditorLetterSpacing = 0.5.sp

/**
 * 双指缩放的上下限（相对 [EditorFontSize] 的倍率）。
 *
 * 下限 0.6 倍 ≈ 8.4sp（再小在手机上看不清了），上限 3 倍 = 42sp（一屏放不下几行，
 * 但看一条特别长的行够用）。
 */
private const val EDITOR_ZOOM_MIN = 0.6f
private const val EDITOR_ZOOM_MAX = 3f

/** 正文上下留白（行号槽与正文共用同一份，否则第一行就对不齐）。 */
private val EditorVerticalPadding = 12.dp

/** 行号槽左内边距。 */
private val EditorGutterStart = 6.dp

/** 行号槽**每一位数字**的宽度（14sp 等宽 + 0.5sp 字距 ≈ 8.9dp，取 9dp 留一点余量）。 */
private val EditorGutterDigit = 9.dp

/** 行号槽右内边距（那条竖线画在它的右缘）。 */
private val EditorGutterEnd = 8.dp

/** 行号槽那条竖线的宽度（px 由 `drawBehind` 里换算，这里只存 dp 值）。 */
private val EditorGutterLineWidth = 1.dp

/** 正文左内边距（与行号槽的竖线之间留一口气）。 */
private val EditorTextStart = 12.dp

/** 正文右内边距。 */
private val EditorTextEnd = 16.dp

/** 右下那颗圆钮的直径（与"回到顶部"那颗同档）。 */
private val EditorChipSize = 44.dp

/** 圆钮与气泡的描边宽度（用户口径"淡淡的灰边"）。 */
private val EditorChipBorderWidth = 1.dp

/** 气泡的圆角。 */
private val EditorBubbleRadius = 8.dp

/** 圆钮距屏幕右下角的距离。 */
private val EditorChipMargin = 16.dp

/** 正文下方的留白：让出右下那颗圆钮（直径 + 外边距 + 一口气）的位置。 */
private val EditorChipReserve = 60.dp

/** 气泡出现/消失的时长（与全仓那几条小过渡同档）。 */
private const val EDITOR_CHIP_FADE_MILLIS = 160
/** 读不到 / 读取中这两句的落位（居中一行，次级色）—— 与正文同一块地方，不另起一屏。 */
@Composable
private fun ColumnScope.EditorNotice(text: String) {
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ThemeColors.textTertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 编辑器语法高亮：为 [relPath] 持有一个后台高亮会话，把正文 [text] 的最新着色结果投成
 * [AnnotatedString]。null = 按纯文本画（未供宿主口 / 非 JS / 原生库缺席 / 结果还没回来）。
 *
 * 会话随 [relPath] 换（换文件才重开），组合离开时关；正文每次变化只投给会话，防抖与
 * 过期结果的丢弃都在会话内部（见 [EditorHighlightSession]）。
 */
@Composable
private fun rememberEditorHighlight(text: String, relPath: String): AnnotatedString? {
    // 语法高亮会话（组合期间持有，换文件时才换；正文变化时投进去）。factory 在会话的
    // worker 线程上被调用 —— 开原生会话（含 dlopen）不在组合线程上做。
    val host = LocalEditorHighlightHost.current
    val dark = isDarkTheme()
    val session = remember(relPath) {
        host?.let { editorHost ->
            EditorHighlightSession(relPath, factory = { path: String -> editorHost.open(path) }).also { it.start() }
        }
    }
    DisposableEffect(session) {
        onDispose { session?.close() }
    }
    LaunchedEffect(session, text) {
        session?.submitSource(text)
    }
    // 会话缺席时也照常订阅（回 null 的那份常量流），不把 composable 调用放进条件分支。
    val highlightResult by (session?.result ?: NoHighlightResult).collectAsState()
    val spans = highlightResult?.spans.orEmpty()
    // 贴色：一行都没贴上（纯文本、非 JS、原生缺席、区间全非法）时回 null，走原来的纯 String 路径。
    return if (spans.isEmpty()) null else highlightedText(text, spans) { kind -> syntaxColor(kind, dark) }
}

/** 高亮会话缺席（未供宿主 / 非 JS / 原生库不可用）时代入的空流：值恒为 null = 纯文本。 */
private val NoHighlightResult: MutableStateFlow<SyntaxHighlightResult?> = MutableStateFlow(null)
