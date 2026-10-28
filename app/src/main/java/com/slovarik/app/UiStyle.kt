package com.slovarik.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal val Night = Color(0xFF111111)
internal val Lavender = Color(0xFFB399FA)
internal val Mint = Color(0xFFC3D7C2)
internal val Coral = Color(0xFFFF6269)
internal val Lemon = Color(0xFFEFFF9C)
internal val Muted = Color(0xFFAAAAAA)

@Composable
internal fun AppIcon(name: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val stroke = 2.dp.toPx()
        fun point(x: Float, y: Float) = Offset(w * x, w * y)
        when (name) {
            "play" -> drawPath(Path().apply { moveTo(w*.34f,w*.22f); lineTo(w*.77f,w*.5f); lineTo(w*.34f,w*.78f); close() }, color)
            "search" -> { drawCircle(color, w*.25f, point(.43f,.43f), style = Stroke(stroke)); drawLine(color, point(.62f,.62f), point(.84f,.84f), stroke, cap = StrokeCap.Round) }
            "folder" -> {
                val path = Path().apply { moveTo(w*.12f,w*.32f); lineTo(w*.38f,w*.32f); lineTo(w*.47f,w*.42f); lineTo(w*.88f,w*.42f); lineTo(w*.83f,w*.78f); lineTo(w*.14f,w*.78f); close() }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "home" -> {
                val path = Path().apply { moveTo(w*.13f,w*.46f); lineTo(w*.5f,w*.16f); lineTo(w*.87f,w*.46f); moveTo(w*.22f,w*.43f); lineTo(w*.22f,w*.83f); lineTo(w*.78f,w*.83f); lineTo(w*.78f,w*.43f); moveTo(w*.43f,w*.83f); lineTo(w*.43f,w*.59f); lineTo(w*.57f,w*.59f); lineTo(w*.57f,w*.83f) }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            "stats" -> { listOf(.22f to .60f, .43f to .39f, .64f to .22f).forEach { (x,y) -> drawLine(color, point(x,.80f), point(x,y), w*.12f, cap = StrokeCap.Round) } }
            "settings" -> { drawCircle(color, w*.28f, point(.5f,.5f), style = Stroke(stroke)); drawCircle(color, w*.09f, point(.5f,.5f)); for (i in 0 until 8) { val a = i * Math.PI / 4; drawLine(color, point((.5 + .34*kotlin.math.cos(a)).toFloat(), (.5 + .34*kotlin.math.sin(a)).toFloat()), point((.5 + .43*kotlin.math.cos(a)).toFloat(), (.5 + .43*kotlin.math.sin(a)).toFloat()), stroke, cap = StrokeCap.Round) } }
            "sound" -> { drawPath(Path().apply { moveTo(w*.14f,w*.4f); lineTo(w*.32f,w*.4f); lineTo(w*.52f,w*.22f); lineTo(w*.52f,w*.78f); lineTo(w*.32f,w*.6f); lineTo(w*.14f,w*.6f); close() }, color, style = Stroke(stroke)); drawLine(color, point(.65f,.35f), point(.75f,.5f), stroke); drawLine(color, point(.75f,.5f), point(.65f,.65f), stroke) }
        }
    }
}
