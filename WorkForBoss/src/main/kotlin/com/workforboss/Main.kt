package com.workforboss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.Scaffold
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
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
        ry = -rx - rz
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
    var selected by remember { mutableStateOf(0) }
    val tabs = listOf("中国跳棋", "页面二", "页面三", "页面四")
    Scaffold(
        topBar = {
            TabRow(selectedTabIndex = selected, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selected) {
                0 -> ChineseCheckersBoard(Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("待实现") }
            }
        }
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "WorkForBoss",
        state = rememberWindowState(width = 1200.dp, height = 900.dp)
    ) {
        App()
    }
}

