package com.directrouter.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live operational status reflected by [DirectRouterBrandMark].
 */
enum class BrandStatus {
    IDLE,
    STARTING,
    ACTIVE,
    ERROR,
}

private object BrandPalette {
    val ObsidianOuter = Color(0xFF060B16)
    val ObsidianCenter = Color(0xFF0D1F3C)
    val HexCoreInner = Color(0xFF15375E)
    val HexCoreOuter = Color(0xFF081427)

    val ElectricCyanDeep = Color(0xFF0EA5E9)
    val ElectricCyan = Color(0xFF38BDF8)
    val ElectricCyanBright = Color(0xFF7DD3FC)

    val EmeraldTunnel = Color(0xFF10B981)
    val EmeraldBright = Color(0xFF34D399)
    val MintHighlight = Color(0xFFECFDF5)

    val StatusActive = Color(0xFF10B981)
    val StatusStarting = Color(0xFFF59E0B)
    val StatusIdle = Color(0xFF38BDF8)
    val StatusError = Color(0xFFEF4444)
}

/**
 * Reusable "Pulso Eléctrico / Mesh-Node" vector brand mark for the DirectRouter TopAppBar.
 *
 * Renders the geometric hexagonal Mesh-Node emblem (dual directional electric cyan arcs,
 * hexagonal core with portal openings, and emerald direct tunnel bridge) inside an obsidian
 * medallion tuned for Light, Dark, and AMOLED themes, paired with a live status indicator dot.
 */
@Composable
fun DirectRouterBrandMark(
    status: BrandStatus = BrandStatus.IDLE,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    showStatusIndicator: Boolean = true,
    contentDescription: String? = null,
) {
    val surfaceBg = MaterialTheme.colorScheme.background
    val bgLuminance = surfaceBg.luminance()
    val isLightTheme = bgLuminance > 0.5f
    val isAmoledTheme = bgLuminance < 0.01f

    val targetStatusColor = when (status) {
        BrandStatus.ACTIVE -> BrandPalette.StatusActive
        BrandStatus.STARTING -> BrandPalette.StatusStarting
        BrandStatus.IDLE -> BrandPalette.StatusIdle
        BrandStatus.ERROR -> BrandPalette.StatusError
    }
    val statusColor by animateColorAsState(
        targetValue = targetStatusColor,
        animationSpec = tween(durationMillis = 280),
        label = "brandStatusColor",
    )

    val pulseProgress = if (
        showStatusIndicator && (status == BrandStatus.ACTIVE || status == BrandStatus.STARTING)
    ) {
        rememberStatusPulseProgress(
            durationMillis = if (status == BrandStatus.STARTING) 950 else 1600,
        )
    } else {
        0f
    }

    val rimColor = when {
        isLightTheme -> BrandPalette.ElectricCyanDeep.copy(alpha = 0.32f)
        isAmoledTheme -> BrandPalette.ElectricCyan.copy(alpha = 0.42f)
        else -> BrandPalette.ElectricCyan.copy(alpha = 0.28f)
    }

    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        modifier
    }

    Canvas(
        modifier = semanticsModifier.size(size),
    ) {
        val diameter = this.size.minDimension
        val radius = diameter / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        // 1. Obsidian-blue medallion backdrop for crisp contrast in Light, Dark & AMOLED themes
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to BrandPalette.ObsidianCenter,
                    0.65f to Color(0xFF091529),
                    1.0f to BrandPalette.ObsidianOuter,
                ),
                center = Offset(center.x, center.y - radius * 0.1f),
                radius = radius * 1.15f,
            ),
            radius = radius,
            center = center,
        )

        // Subtle ambient status aura inside medallion
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    statusColor.copy(alpha = 0.22f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius * 0.82f,
            ),
            radius = radius * 0.82f,
            center = center,
        )

        // Crisp medallion perimeter rim
        drawCircle(
            color = rimColor,
            radius = radius - (diameter * 0.018f),
            center = center,
            style = Stroke(width = (diameter * 0.036f).coerceAtLeast(1f)),
        )

        // 2. Dual directional electric cyan orbital arcs (r = 0.69 * medallion radius)
        val arcRadius = radius * 0.69f
        val arcStrokeWidth = diameter * 0.068f
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

        // Upper arc: left-top -> right-top (214° to 326°, sweep 112°)
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    BrandPalette.ElectricCyanDeep,
                    BrandPalette.ElectricCyan,
                    BrandPalette.ElectricCyanBright,
                ),
                start = Offset(center.x - arcRadius, center.y - arcRadius * 0.6f),
                end = Offset(center.x + arcRadius, center.y - arcRadius * 0.6f),
            ),
            startAngle = 214f,
            sweepAngle = 112f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round),
        )

        // Lower arc: right-bottom -> left-bottom (34° to 146°, sweep 112°)
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    BrandPalette.ElectricCyanDeep,
                    BrandPalette.ElectricCyan,
                    BrandPalette.ElectricCyanBright,
                ),
                start = Offset(center.x + arcRadius, center.y + arcRadius * 0.6f),
                end = Offset(center.x - arcRadius, center.y + arcRadius * 0.6f),
            ),
            startAngle = 34f,
            sweepAngle = 112f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round),
        )

        // Directional arc chevrons
        drawArcChevron(
            center = center,
            arcRadius = arcRadius,
            endAngleDeg = 326f,
            armLength = diameter * 0.115f,
            strokeWidth = diameter * 0.058f,
            color = BrandPalette.ElectricCyanBright,
        )
        drawArcChevron(
            center = center,
            arcRadius = arcRadius,
            endAngleDeg = 146f,
            armLength = diameter * 0.115f,
            strokeWidth = diameter * 0.058f,
            color = BrandPalette.ElectricCyanBright,
        )

        // 3. Central hexagonal Mesh-Node core (pointy-top hexagon with tunnel portals)
        val hexRadius = radius * 0.43f
        val hexHalfWidth = hexRadius * 0.8660254f
        val hexHalfHeight = hexRadius * 0.5f
        val portalHalfGap = hexRadius * 0.24f

        val hexFillPath = Path().apply {
            moveTo(center.x, center.y - hexRadius)
            lineTo(center.x + hexHalfWidth, center.y - hexHalfHeight)
            lineTo(center.x + hexHalfWidth, center.y + hexHalfHeight)
            lineTo(center.x, center.y + hexRadius)
            lineTo(center.x - hexHalfWidth, center.y + hexHalfHeight)
            lineTo(center.x - hexHalfWidth, center.y - hexHalfHeight)
            close()
        }
        drawPath(
            path = hexFillPath,
            brush = Brush.radialGradient(
                colors = listOf(BrandPalette.HexCoreInner, BrandPalette.HexCoreOuter),
                center = Offset(center.x, center.y - hexRadius * 0.2f),
                radius = hexRadius * 1.2f,
            ),
        )

        val hexBracketsPath = Path().apply {
            // Upper bracket
            moveTo(center.x - hexHalfWidth, center.y - portalHalfGap)
            lineTo(center.x - hexHalfWidth, center.y - hexHalfHeight)
            lineTo(center.x, center.y - hexRadius)
            lineTo(center.x + hexHalfWidth, center.y - hexHalfHeight)
            lineTo(center.x + hexHalfWidth, center.y - portalHalfGap)

            // Lower bracket
            moveTo(center.x + hexHalfWidth, center.y + portalHalfGap)
            lineTo(center.x + hexHalfWidth, center.y + hexHalfHeight)
            lineTo(center.x, center.y + hexRadius)
            lineTo(center.x - hexHalfWidth, center.y + hexHalfHeight)
            lineTo(center.x - hexHalfWidth, center.y + portalHalfGap)
        }
        drawPath(
            path = hexBracketsPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    BrandPalette.ElectricCyanBright,
                    BrandPalette.ElectricCyan,
                    BrandPalette.ElectricCyanDeep,
                ),
                start = Offset(center.x - hexHalfWidth, center.y - hexRadius),
                end = Offset(center.x + hexHalfWidth, center.y + hexRadius),
            ),
            style = Stroke(
                width = diameter * 0.064f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // 4. Emerald direct device-to-device tunnel bridge + peer endpoints + luminous core
        val nodeOffsetX = radius * 0.65f
        val leftNode = Offset(center.x - nodeOffsetX, center.y)
        val rightNode = Offset(center.x + nodeOffsetX, center.y)
        val bridgeStroke = diameter * 0.064f

        // Soft bridge glow
        drawLine(
            color = BrandPalette.EmeraldTunnel.copy(alpha = 0.30f),
            start = leftNode,
            end = rightNode,
            strokeWidth = bridgeStroke * 2.1f,
            cap = StrokeCap.Round,
        )

        // Core emerald tunnel line
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    BrandPalette.EmeraldTunnel,
                    BrandPalette.EmeraldBright,
                    BrandPalette.EmeraldTunnel,
                ),
                startX = leftNode.x,
                endX = rightNode.x,
            ),
            start = leftNode,
            end = rightNode,
            strokeWidth = bridgeStroke,
            cap = StrokeCap.Round,
        )

        // Left & right peer device nodes
        val peerNodeRadius = diameter * 0.068f
        drawCircle(color = BrandPalette.EmeraldTunnel, radius = peerNodeRadius, center = leftNode)
        drawCircle(color = BrandPalette.MintHighlight, radius = peerNodeRadius * 0.42f, center = leftNode)
        drawCircle(color = BrandPalette.EmeraldTunnel, radius = peerNodeRadius, center = rightNode)
        drawCircle(color = BrandPalette.MintHighlight, radius = peerNodeRadius * 0.42f, center = rightNode)

        // Central Mesh-Node core dot
        val coreRadius = diameter * 0.088f
        drawCircle(
            color = BrandPalette.EmeraldTunnel.copy(alpha = 0.34f),
            radius = coreRadius * 1.55f,
            center = center,
        )
        drawCircle(color = BrandPalette.EmeraldTunnel, radius = coreRadius, center = center)
        drawCircle(color = BrandPalette.MintHighlight, radius = coreRadius * 0.48f, center = center)

        // 5. Live status indicator badge at bottom-right
        if (showStatusIndicator) {
            val dotRadius = (diameter * 0.145f).coerceAtLeast(3.5.dp.toPx())
            val dotCenter = Offset(
                x = center.x + radius * 0.72f,
                y = center.y + radius * 0.72f,
            )

            // Expanding live pulse ring while ACTIVE or STARTING
            if (pulseProgress > 0f) {
                drawCircle(
                    color = statusColor.copy(alpha = (1f - pulseProgress) * 0.55f),
                    radius = dotRadius * (1.0f + pulseProgress * 0.95f),
                    center = dotCenter,
                )
            }

            // Cutout ring matching the TopAppBar background so the dot stays crisp
            drawCircle(
                color = surfaceBg,
                radius = dotRadius * 1.38f,
                center = dotCenter,
            )

            // Status dot fill
            drawCircle(
                color = statusColor,
                radius = dotRadius,
                center = dotCenter,
            )
        }
    }
}

@Composable
private fun rememberStatusPulseProgress(durationMillis: Int): Float {
    val transition = rememberInfiniteTransition(label = "brandStatusPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "brandStatusPulseProgress",
    )
    return progress
}

private fun DrawScope.drawArcChevron(
    center: Offset,
    arcRadius: Float,
    endAngleDeg: Float,
    armLength: Float,
    strokeWidth: Float,
    color: Color,
) {
    val rad = endAngleDeg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    val tip = Offset(center.x + arcRadius * cosA, center.y + arcRadius * sinA)
    // Clockwise unit tangent at endAngleDeg is (-sinA, cosA); outward normal is (cosA, sinA)
    val tx = -sinA
    val ty = cosA
    val nx = cosA
    val ny = sinA

    val back = armLength * 0.85f
    val spread = armLength * 0.78f

    val outerWing = Offset(
        x = tip.x - back * tx + spread * nx,
        y = tip.y - back * ty + spread * ny,
    )
    val innerWing = Offset(
        x = tip.x - back * tx - spread * nx,
        y = tip.y - back * ty - spread * ny,
    )

    val chevronPath = Path().apply {
        moveTo(outerWing.x, outerWing.y)
        lineTo(tip.x, tip.y)
        lineTo(innerWing.x, innerWing.y)
    }
    drawPath(
        path = chevronPath,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A101D)
@Composable
private fun DirectRouterBrandMarkActivePreview() {
    MaterialTheme {
        DirectRouterBrandMark(status = BrandStatus.ACTIVE, size = 40.dp)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F6FB)
@Composable
private fun DirectRouterBrandMarkLightIdlePreview() {
    MaterialTheme {
        DirectRouterBrandMark(status = BrandStatus.IDLE, size = 40.dp)
    }
}
