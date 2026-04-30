package com.xixin.codent.wrapper.log

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ✅ 核心包名替换
import com.xixin.codent.wrapper.copy.copy

// ══════════════════════════════════════════════════════════════
//  持久化存储层
// ══════════════════════════════════════════════════════════════

private const val PREFS_NAME     = "debug_console_prefs"
private const val KEY_HEIGHT     = "panel_height"
private const val KEY_WIDTH      = "panel_width"
private const val KEY_ALPHA      = "panel_alpha"
private const val KEY_FONT_SIZE  = "log_font_size"

private data class ConsoleSettings(
    val panelHeight : Float = UNSET,
    val panelWidth  : Float = UNSET,
    val alpha       : Float = DEFAULT_ALPHA,
    val fontSize    : Float = DEFAULT_FONT_SP
) {
    companion object {
        const val UNSET           = -1f
        const val DEFAULT_ALPHA   = 0.93f
        const val DEFAULT_FONT_SP = 11f
    }
}

private fun SharedPreferences.loadConsoleSettings() = ConsoleSettings(
    panelHeight = getFloat(KEY_HEIGHT,    ConsoleSettings.UNSET),
    panelWidth  = getFloat(KEY_WIDTH,     ConsoleSettings.UNSET),
    alpha       = getFloat(KEY_ALPHA,     ConsoleSettings.DEFAULT_ALPHA),
    fontSize    = getFloat(KEY_FONT_SIZE, ConsoleSettings.DEFAULT_FONT_SP)
)

private fun SharedPreferences.Editor.saveConsoleSettings(s: ConsoleSettings) {
    putFloat(KEY_HEIGHT,    s.panelHeight)
    putFloat(KEY_WIDTH,     s.panelWidth)
    putFloat(KEY_ALPHA,     s.alpha)
    putFloat(KEY_FONT_SIZE, s.fontSize)
    apply()
}

// ══════════════════════════════════════════════════════════════
//  全局日志管理
// ══════════════════════════════════════════════════════════════

object AppLog {
var isVisible by mutableStateOf(false)

    enum class Level(val color: Color) {
        DEBUG(Color(0xFF64B5F6)),
        INFO (Color(0xFF81C784)),
        WARN (Color(0xFFFFB74D)),
        ERROR(Color(0xFFE57373))
    }

    data class LogEntry(
        val time    : String,
        val level   : Level,
        val content : String,
        val id      : Long = System.nanoTime()
    )

    private const val MAX_LOGS = 1500
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    fun log(msg: String, level: Level = Level.DEBUG) {
        val time = java.text.SimpleDateFormat("mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        if (_logs.size >= MAX_LOGS) _logs.removeAt(0)
        _logs.add(LogEntry(time, level, msg))
    }

    fun log(obj: Any?, level: Level = Level.DEBUG) {
        log(obj.toString(), level)
    }

    fun d(msg: String) = log(msg, Level.DEBUG)
    fun d(obj: Any?) = log(obj, Level.DEBUG)

    fun i(msg: String) = log(msg, Level.INFO)
    fun i(obj: Any?) = log(obj, Level.INFO)

    fun w(msg: String) = log(msg, Level.WARN)
    fun w(obj: Any?) = log(obj, Level.WARN)

    fun e(msg: String) = log(msg, Level.ERROR)
    fun e(obj: Any?) = log(obj, Level.ERROR)
    fun clear() = _logs.clear()

    fun getAllText(): String = _logs.joinToString("\n") { formatEntry(it) }

    fun formatEntry(entry: LogEntry): String = "[${entry.time}] [${entry.level.name}] ${entry.content}"
}

// ══════════════════════════════════════════════════════════════
//  自绘滚动条 Modifier 扩展
// ══════════════════════════════════════════════════════════════

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 3.dp,
    color: Color = Color.White.copy(alpha = 0.4f),
    minThumbH: Float = 40f
): Modifier {
    val density = LocalDensity.current
    val firstIndex = state.firstVisibleItemIndex
    val scrollOffset = state.firstVisibleItemScrollOffset

    return drawWithContent {
        try {
            drawContent()
            val layoutInfo = state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val visibleItems = layoutInfo.visibleItemsInfo

            if (totalItems <= 0 || visibleItems.isEmpty()) return@drawWithContent

            var totalVisibleHeight = 0f
            for (item in visibleItems) {
                totalVisibleHeight += item.size.toFloat()
            }
            val avgItemHeight = totalVisibleHeight / visibleItems.size
            if (avgItemHeight <= 0f) return@drawWithContent

            val totalContentHeight = avgItemHeight * totalItems
            val viewportHeight = size.height
            if (totalContentHeight <= viewportHeight || viewportHeight <= 0f) return@drawWithContent

            val scrolledPx = firstIndex * avgItemHeight + scrollOffset
            val maxScroll = totalContentHeight - viewportHeight
            if (maxScroll <= 0f) return@drawWithContent

            val thumbH = max(minThumbH, viewportHeight * (viewportHeight / totalContentHeight))
            val thumbTop = (viewportHeight - thumbH) * (scrolledPx / maxScroll)
            val barW = with(density) { width.toPx() }
            if (barW <= 0f) return@drawWithContent

            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - barW - 2f, thumbTop.coerceIn(0f, viewportHeight - thumbH)),
                size = Size(barW, thumbH),
                cornerRadius = CornerRadius(barW / 2)
            )
        } catch (e: Exception) {}
    }
}

// ══════════════════════════════════════════════════════════════
//  私有 UI 组件 (注入物理阻尼)
// ══════════════════════════════════════════════════════════════

@Composable
private fun SmallIconBtn(
    onClick : () -> Unit,
    enabled : Boolean = true,
    content : @Composable () -> Unit
) {
    // 🌟 筑造师注入：面板按钮物理微缩放反馈
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
        label = "btn_scale"
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        interactionSource = interactionSource
    ) { content() }
}

@Composable
private fun SettingSliderRow(
    label       : String,
    value       : Float,
    valueRange  : ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = valueRange,
            modifier      = Modifier.weight(1f)
        )
        Text(
            "${value.roundToInt()}",
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.outline,
            modifier  = Modifier.width(34.dp),
            textAlign = TextAlign.End
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  LogItem
// ══════════════════════════════════════════════════════════════

@Composable
fun LogItem(
    index          : Int,
    log            : AppLog.LogEntry,
    isFocused      : Boolean = false,
    logFontSizeSp  : Float   = ConsoleSettings.DEFAULT_FONT_SP,
    onCopySingle   : (AppLog.LogEntry) -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue    = if (isFocused) log.level.color.copy(alpha = 0.85f)
                         else log.level.color.copy(alpha = 0.2f),
        animationSpec  = tween(250),
        label          = "border"
    )
    val bgColor by animateColorAsState(
        targetValue    = if (isFocused) log.level.color.copy(alpha = 0.1f)
                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec  = tween(250),
        label          = "bg"
    )

    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = bgColor,
        border   = BorderStroke(if (isFocused) 1.dp else 0.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.size(7.dp).background(log.level.color, CircleShape))
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = log.level.color,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Text(
                    text = log.time,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(log.level.color.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        log.level.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = log.level.color,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp
                    )
                }
                Spacer(Modifier.width(4.dp))

                Box(
                    Modifier.size(20.dp).clip(CircleShape).clickable { onCopySingle(log) },
                    Alignment.Center
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(5.dp))

            SelectionContainer {
                Text(
                    text = log.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = logFontSizeSp.sp,
                        lineHeight = (logFontSizeSp * 1.5f).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  DebugFloatingConsole — 主组件
// ══════════════════════════════════════════════════════════════

@Composable
fun DebugFloatingConsole() {

    val context = LocalContext.current
    
    val isInitialized = remember { mutableStateOf(true) }
    
    val appPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    // 👇 新增：App启动时读取本地记忆
    if (!isInitialized.value) {
        // 读取记忆，如果没存过，默认是 false（关闭）
        AppLog.isVisible = appPrefs.getBoolean("log_is_visible", false)
        isInitialized.value = true
    }
    
    if (!AppLog.isVisible) return



    
    val scope   = rememberCoroutineScope()
    val config  = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx  = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val ballSizeDp = 48.dp
    val ballSizePx = with(density) { ballSizeDp.toPx() }

    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).loadConsoleSettings()
    }

    var skipListDuringSizeAnim by remember { mutableStateOf(false) }
    
    var panelHeightDp by remember { mutableStateOf(if (prefs.panelHeight != ConsoleSettings.UNSET) prefs.panelHeight else 250f) }
    var panelWidthDp  by remember { mutableStateOf(if (prefs.panelWidth != ConsoleSettings.UNSET) prefs.panelWidth else 200f) }
    var panelAlpha    by remember { mutableStateOf(prefs.alpha) }
    var logFontSize   by remember { mutableStateOf(prefs.fontSize) }

    LaunchedEffect(panelHeightDp, panelWidthDp, panelAlpha, logFontSize) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .saveConsoleSettings(ConsoleSettings(panelHeightDp, panelWidthDp, panelAlpha, logFontSize))
    }

    var isExpanded    by remember { mutableStateOf(false) }
    var searchQuery   by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var showSettings  by remember { mutableStateOf(false) }
    var focusedIndex  by remember { mutableStateOf(-1) }

    val animOffsetX = remember { Animatable(-1f) }
    val animOffsetY = remember { Animatable(-1f) }

    LaunchedEffect(Unit) {
        if (animOffsetX.value < 0f) {
            animOffsetX.snapTo(screenWidthPx - ballSizePx - 24f)
            animOffsetY.snapTo(screenHeightPx - ballSizePx - 160f)
        }
    }

    val isOnRightSide by remember {
        derivedStateOf { animOffsetX.value + ballSizePx / 2 > screenWidthPx / 2 }
    }

    val maxPanelWidthPx = screenWidthPx - 24f

    val animatedWidth by animateDpAsState(
        targetValue   = if (isExpanded) panelWidthDp.dp else ballSizeDp,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
        label         = "width"
    )
    val animatedHeight by animateDpAsState(
        targetValue = when {
            !isExpanded  -> ballSizeDp
            showSettings -> panelHeightDp.dp + 100.dp
            else         -> panelHeightDp.dp
        },
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
        label         = "height"
    )

    val panelWidthPx = with(density) { animatedWidth.toPx() }

    val filteredLogs by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isEmpty()) AppLog.logs
            else AppLog.logs.filter { it.content.contains(searchQuery, ignoreCase = true) }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in filteredLogs.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }
    LaunchedEffect(filteredLogs.size) {
        if (focusedIndex == -1 && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .offset {
                    val rawX = if (isExpanded && isOnRightSide) {
                        animOffsetX.value - (panelWidthPx - ballSizePx)
                    } else {
                        animOffsetX.value
                    }
                    val clampedX = rawX.coerceIn(0f, screenWidthPx - if (isExpanded) panelWidthPx else ballSizePx)
                    IntOffset(clampedX.roundToInt(), animOffsetY.value.roundToInt())
                }
                .size(width = animatedWidth, height = animatedHeight)
                .pointerInput(isExpanded) {
                    detectDragGestures(
                        onDragEnd = {
                            if (!isExpanded) {
                                scope.launch {
                                    val targetX = if (animOffsetX.value + ballSizePx / 2 < screenWidthPx / 2) 20f else screenWidthPx - ballSizePx - 20f
                                    animOffsetX.animateTo(targetX, spring())
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                animOffsetX.snapTo((animOffsetX.value + drag.x).coerceIn(0f, screenWidthPx - ballSizePx))
                                animOffsetY.snapTo((animOffsetY.value + drag.y).coerceIn(-ballSizePx * 0.5f, screenHeightPx - ballSizePx * 0.5f))
                            }
                        }
                    )
                }
                .shadow(16.dp, RoundedCornerShape(24.dp)),
            shape  = RoundedCornerShape(24.dp),
            color  = MaterialTheme.colorScheme.surface.copy(alpha = panelAlpha),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Crossfade(targetState = isExpanded, label = "expand") { expanded ->
                if (!expanded) {
                    FloatingBall(onClick = { 
                        skipListDuringSizeAnim = true
                        isExpanded = true
                        focusedIndex = -1
                        scope.launch {
                            delay(200)
                            skipListDuringSizeAnim = false
                        }
                    })
                } else {
                    ExpandedPanel(
                        filteredLogs    = filteredLogs,
                        listState       = listState,
                        searchQuery     = searchQuery,
                        showSearchBar   = showSearchBar,
                        showSettings    = showSettings,
                        focusedIndex    = focusedIndex,
                        panelHeightDp   = panelHeightDp,
                        panelWidthDp    = panelWidthDp,
                        panelAlpha      = panelAlpha,
                        logFontSize     = logFontSize,
                        maxPanelWidthPx = maxPanelWidthPx,
                        skipListDuringSizeAnim = skipListDuringSizeAnim,
                        density         = density,
                        onSearchChange  = { searchQuery = it; focusedIndex = -1 },
                        onToggleSearch  = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) { searchQuery = ""; focusedIndex = -1 }
                            showSettings = false
                        },
                        onToggleSettings = {
                            showSettings = !showSettings
                            showSearchBar = false
                        },
                        onClear         = { AppLog.clear(); focusedIndex = -1 },
                        onPrev          = {
                            if (filteredLogs.isNotEmpty())
                                focusedIndex = if (focusedIndex <= 0) filteredLogs.lastIndex else focusedIndex - 1
                        },
                        onNext          = {
                            if (filteredLogs.isNotEmpty())
                                focusedIndex = if (focusedIndex >= filteredLogs.lastIndex) 0 else focusedIndex + 1
                        },
                        onCopyAll       = {
                            context.copy(AppLog.getAllText())
                            Toast.makeText(context, "已复制全部 ${AppLog.logs.size} 条", Toast.LENGTH_SHORT).show()
                        },
                        onCopyEntry     = { entry ->
                            context.copy(AppLog.formatEntry(entry))
                            Toast.makeText(context, "已复制该条日志", Toast.LENGTH_SHORT).show()
                        },
                        onClose = {
                            skipListDuringSizeAnim = true
                            isExpanded = false
                            showSearchBar = false
                            showSettings = false
                            focusedIndex = -1
                            scope.launch {
                                delay(200)
                                skipListDuringSizeAnim = false
                            }
                        },
                        onHeightChange  = { panelHeightDp = it },
                        onWidthChange   = { panelWidthDp  = it },
                        onAlphaChange   = { panelAlpha    = it },
                        onFontSizeChange= { logFontSize   = it }
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  FloatingBall
// ══════════════════════════════════════════════════════════════

@Composable
private fun FloatingBall(onClick: () -> Unit) {
    // 🌟 筑造师注入：悬浮球自身的物理微缩放反馈
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
        label = "ball_scale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null, // 去除默认水波纹，仅保留缩放物理感
                onClick = onClick
            ),
        Alignment.Center
    ) {
        Icon(Icons.Default.BugReport, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)

        if (AppLog.logs.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                Alignment.Center
            ) {
                Text(
                    text       = if (AppLog.logs.size > 99) "99+" else "${AppLog.logs.size}",
                    fontSize   = 7.sp,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  ExpandedPanel
// ══════════════════════════════════════════════════════════════

@Composable
private fun ExpandedPanel(
    filteredLogs     : List<AppLog.LogEntry>,
    listState        : LazyListState,
    searchQuery      : String,
    showSearchBar    : Boolean,
    showSettings     : Boolean,
    focusedIndex     : Int,
    panelHeightDp    : Float,
    panelWidthDp     : Float,
    panelAlpha       : Float,
    logFontSize      : Float,
    maxPanelWidthPx  : Float,
    density          : androidx.compose.ui.unit.Density,
    onSearchChange   : (String) -> Unit,
    onToggleSearch   : () -> Unit,
    onToggleSettings : () -> Unit,
    onClear          : () -> Unit,
    onPrev           : () -> Unit,
    onNext           : () -> Unit,
    onCopyAll        : () -> Unit,
    onCopyEntry      : (AppLog.LogEntry) -> Unit,
    onClose          : () -> Unit,
    onHeightChange   : (Float) -> Unit,
    onWidthChange    : (Float) -> Unit,
    onAlphaChange    : (Float) -> Unit,
    onFontSizeChange : (Float) -> Unit,
    skipListDuringSizeAnim : Boolean
) {
    val maxPanelWidthDp = with(density) { maxPanelWidthPx.toDp().value }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(start = 4.dp)) {
                Crossfade(targetState = showSearchBar, label = "search_mode") { searching ->
                    if (searching) {
                        BasicTextField(
                            value         = searchQuery,
                            onValueChange = onSearchChange,
                            textStyle     = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine  = true
                        )
                    } else {
                        Text("Logs${if (AppLog.logs.isNotEmpty()) " (${AppLog.logs.size})" else ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                }
            }

            SmallIconBtn(onClick = onToggleSearch) { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }
            SmallIconBtn(onClick = onToggleSettings) { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)) }
            SmallIconBtn(onClick = onClear) { Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
            SmallIconBtn(onClick = onPrev, enabled = filteredLogs.size > 1) { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp)) }
            SmallIconBtn(onClick = onNext, enabled = filteredLogs.size > 1) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp)) }
            SmallIconBtn(onClick = onCopyAll) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) }
            SmallIconBtn(onClick = onClose) { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
        }

        HorizontalDivider(thickness = 0.4.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

        AnimatedVisibility(visible = showSettings) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SettingSliderRow("高度", panelHeightDp, 120f..600f, onHeightChange)
                SettingSliderRow("宽度", panelWidthDp, 200f..maxPanelWidthDp, { onWidthChange(it.coerceAtMost(maxPanelWidthDp)) })
                SettingSliderRow("字号", logFontSize, 9f..16f, onFontSizeChange)
                SettingSliderRow("透明", (panelAlpha * 100).roundToInt().toFloat(), 30f..100f, { onAlphaChange(it / 100f) })
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text(if (searchQuery.isEmpty()) "暂无日志" else "无匹配「$searchQuery」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            if (skipListDuringSizeAnim) {
                Box(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).verticalScrollbar(listState),
                    contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    itemsIndexed(filteredLogs, key = { _, item -> item.id }) { idx, log ->
                        val focused = idx == focusedIndex
                        LogItem(index = idx, log = log, isFocused = focused, logFontSizeSp = logFontSize, onCopySingle = onCopyEntry)
                    }
                }
            }
        }
    }
}
