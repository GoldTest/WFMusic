package com.workforboss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.inset
import java.awt.Toolkit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import com.workforboss.music.MusicPlayerTab
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Axial(val q: Int, val r: Int)

fun generateChineseCheckersCells(radius: Int = 4): List<Axial> {
    val R = radius
    val limit = 2 * R
    val cells = ArrayList<Axial>()
    for (q in -limit..limit) {
        for (r in -limit..limit) {
            val y = -q - r
            val absq = kotlin.math.abs(q)
            val absr = kotlin.math.abs(r)
            val absy = kotlin.math.abs(y)
            val center = absq <= R && absr <= R && absy <= R
            val arm = (absq > R && absr <= R && absy <= R) ||
                      (absr > R && absq <= R && absy <= R) ||
                      (absy > R && absq <= R && absr <= R
                      )
            if ((absq <= limit && absr <= limit && absy <= limit) && (center || arm)) {
                cells.add(Axial(q, r))
            }
        }
    }
    return cells
}

fun axialToPixel(a: Axial, hexSize: Float): Offset {
    val x = hexSize * ((sqrt(3.0) * a.q) + (sqrt(3.0) / 2.0 * a.r)).toFloat()
    val y = hexSize * (1.5f * a.r)
    return Offset(x, y)
}

fun axialRound(q: Double, r: Double): Axial {
    val x = q
    val z = r
    val y = -x - z
    var rx = kotlin.math.round(x).toInt()
    var ry = kotlin.math.round(y).toInt()
    var rz = kotlin.math.round(z).toInt()
    val xDiff = kotlin.math.abs(rx - x)
    val yDiff = kotlin.math.abs(ry - y)
    val zDiff = kotlin.math.abs(rz - z)
    if (xDiff > yDiff && xDiff > zDiff) {
        rx = -ry - rz
    } else if (yDiff > zDiff) {
        // ry = -rx - rz // ry is not used in Axial(rx, rz)
    } else {
        rz = -rx - ry
    }
    return Axial(rx, rz)
}

fun pixelToAxial(p: Offset, hexSize: Float, offset: Offset): Axial {
    val x = (p.x - offset.x).toDouble()
    val y = (p.y - offset.y).toDouble()
    val q = ((sqrt(3.0) / 3.0) * x - (1.0 / 3.0) * y) / hexSize
    val r = ((2.0 / 3.0) * y) / hexSize
    return axialRound(q, r)
}

data class Layout(val hexSize: Float, val offset: Offset)

fun computeLayout(cells: List<Axial>, canvasW: Float, canvasH: Float): Layout {
    val s = 1f
    val centers = cells.map { axialToPixel(it, s) }
    val minX = centers.minOf { it.x }
    val maxX = centers.maxOf { it.x }
    val minY = centers.minOf { it.y }
    val maxY = centers.maxOf { it.y }
    val marginX = 0.866f
    val marginY = 1f
    val boundW = (maxX - minX) + 2f * marginX
    val boundH = (maxY - minY) + 2f * marginY
    val sizeScale = 0.95f * kotlin.math.min(canvasW / boundW, canvasH / boundH)
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val offset = Offset(canvasW / 2f - cx * sizeScale, canvasH / 2f - cy * sizeScale)
    return Layout(sizeScale, offset)
}

fun Path.addHex(center: Offset, size: Float) {
    for (i in 0..5) {
        val angle = (PI / 180.0) * (60.0 * i - 30.0)
        val vx = center.x + size * cos(angle).toFloat()
        val vy = center.y + size * sin(angle).toFloat()
        if (i == 0) moveTo(vx, vy) else lineTo(vx, vy)
    }
    close()
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ChineseCheckersBoard(modifier: Modifier = Modifier) {
    val cells = remember { generateChineseCheckersCells(4) }
    var hovered by remember { mutableStateOf<Axial?>(null) }
    val cellSet = remember { cells.toSet() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Canvas(
        modifier = modifier
            .onGloballyPositioned { canvasSize = it.size }
            .onPointerEvent(PointerEventType.Move) { event ->
                val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                val layout = computeLayout(cells, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                val axial = pixelToAxial(pos, layout.hexSize, layout.offset)
                hovered = if (cellSet.contains(axial)) axial else null
            }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = null
            }
    ) {
        val layout = computeLayout(cells, size.width, size.height)
        drawBoard(cells, layout.hexSize, layout.offset, hovered)
    }
}

fun DrawScope.drawBoard(cells: List<Axial>, hexSize: Float, offset: Offset, hovered: Axial?) {
    for (cell in cells) {
        val center = axialToPixel(cell, hexSize) + offset
        val path = Path()
        val isHover = hovered != null && cell == hovered
        val sizeFactor = if (isHover) 1.05f else 0.95f
        val fill = if (isHover) Color(0xFFFFF59D) else Color(0xFFEFEFEF)
        val stroke = if (isHover) Color(0xFF333333) else Color(0xFF555555)
        path.addHex(center, hexSize * sizeFactor)
        drawPath(path, color = fill)
        drawPath(path, color = stroke, style = Stroke(width = if (isHover) 2.5f else 1.5f))
    }
}

@Composable
fun App() {
    var showSettings by remember { mutableStateOf(false) }
    
    // 高级感配色方案
    val premiumDarkColors = darkColors(
        primary = Color(0xFFD0BCFF),
        primaryVariant = Color(0xFF381E72),
        secondary = Color(0xFFCCC2DC),
        background = Color(0xFF1A1C1E),
        surface = Color(0xFF2D2F31),
        onPrimary = Color(0xFF381E72),
        onSecondary = Color(0xFF332D41),
        onBackground = Color(0xFFE2E2E6),
        onSurface = Color(0xFFE2E2E6)
    )

    MaterialTheme(colors = premiumDarkColors) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            // 音乐播放器作为底层主视图，始终存在以维持状态
            MusicPlayerTab(onNavigateToSettings = { showSettings = true })

            // 设置页覆盖在主视图之上
            if (showSettings) {
                SettingsView(onClose = { showSettings = false })
            }
        }
    }
}

@Composable
fun SettingsView(onClose: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("通用", "跳棋", "关于")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                backgroundColor = MaterialTheme.colors.surface,
                elevation = 4.dp
            )

            TabRow(
                selectedTabIndex = selectedTab,
                backgroundColor = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        height = 3.dp,
                        color = MaterialTheme.colors.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                        selectedContentColor = MaterialTheme.colors.primary,
                        unselectedContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTab) {
                    0 -> Column {
                        Text("应用设置", style = MaterialTheme.typography.h6)
                        Spacer(Modifier.height(16.dp))
                        Text("这里可以放置音质选择、下载路径设置等...", color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                    }
                    1 -> ChineseCheckersBoard(Modifier.fillMaxSize())
                    2 -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(32.dp))
                        Text("WorkForBoss", style = MaterialTheme.typography.h5, color = MaterialTheme.colors.primary)
                        Text("版本 0.2.2", style = MaterialTheme.typography.caption)
                        Spacer(Modifier.height(16.dp))
                        Text("一个极简且高级的音乐播放器", style = MaterialTheme.typography.body1)
                    }
                }
            }
        }
    }
}

fun main() = application {
    val screenSize = Toolkit.getDefaultToolkit().screenSize
    val windowWidth = (screenSize.width / 3).coerceAtLeast(600)
    val windowHeight = (screenSize.height * 0.8).toInt()
    
    val x = screenSize.width - windowWidth
    val y = (screenSize.height - windowHeight) / 2

    var isVisible by remember { mutableStateOf(true) }
    val trayState = rememberTrayState()
    val appIcon = painterResource("icon.png")

    Window(
        onCloseRequest = { isVisible = false },
        title = "WFMusic",
        icon = appIcon,
        visible = isVisible,
        state = rememberWindowState(
            position = WindowPosition(x.dp, y.dp),
            width = windowWidth.dp,
            height = windowHeight.dp
        )
    ) {
        App()
    }

    Tray(
        state = trayState,
        icon = appIcon,
        tooltip = "WFMusic",
        onAction = { isVisible = true },
        menu = {
            Item("Show Window", onClick = { isVisible = true })
            Separator()
            Item("Exit", onClick = { exitApplication() })
        }
    )
}

