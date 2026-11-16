package studio.oracle.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import studio.oracle.data.AgentAttachmentRef
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans


@Composable
fun OracleSpinner(
    modifier: Modifier = Modifier.size(18.dp),
    tint: Color = LocalOraclePalette.current.accent
) {
    val transition = rememberInfiniteTransition(label = "oracle-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = LinearEasing)
        ),
        label = "oracle-spinner-rotation"
    )
    Canvas(modifier = modifier) {
        rotate(rotation, pivot = center) {
            drawArc(
                color = tint,
                startAngle = 36f,
                sweepAngle = 258f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.12f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.76f),
                style = Stroke(width = size.minDimension * 0.075f, cap = StrokeCap.Round)
            )
        }
    }
}

enum class OracleGlyph {
    Menu, NewChat, Library, Settings, Back, Close, ArrowUp, ChevronDown,
    Search, Spark, Chat, Refresh, More, Key, Eye, EyeOff, Check, Shield, Trash, Audio, Speaker, SpeakerOff, AddCircle
}

@Composable
fun HandcraftedLogo(
    modifier: Modifier = Modifier,
    color: Color = LocalOraclePalette.current.accent,
    strokeFactor: Float = 1f
) {
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val stroke = diameter * 0.067f * strokeFactor
        val inset = diameter * 0.17f
        val ring = Size(diameter - inset * 2f, diameter - inset * 2f)
        val origin = androidx.compose.ui.geometry.Offset(inset, inset)

        rotate(degrees = 2.2f, pivot = center) {
            drawArc(
                color = color,
                startAngle = 8f,
                sweepAngle = 337f,
                useCenter = false,
                topLeft = origin,
                size = ring,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color.copy(alpha = 0.48f),
                startAngle = 178f,
                sweepAngle = 27f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset + diameter * 0.012f, inset - diameter * 0.008f),
                size = Size(ring.width * 0.985f, ring.height * 1.014f),
                style = Stroke(width = stroke * 0.42f, cap = StrokeCap.Round)
            )
            drawLine(
                color = color.copy(alpha = 0.8f),
                start = androidx.compose.ui.geometry.Offset(diameter * 0.72f, diameter * 0.2f),
                end = androidx.compose.ui.geometry.Offset(diameter * 0.77f, diameter * 0.24f),
                strokeWidth = stroke * 0.44f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun OracleGlyphIcon(
    glyph: OracleGlyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalOraclePalette.current.text,
    strokeWidth: Float = 1.55f
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val sw = strokeWidth * (sx + sy) * 0.5f
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw) {
            drawLine(tint, point(x1, y1), point(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
        }
        fun circle(x: Float, y: Float, radius: Float, width: Float = sw) {
            drawCircle(tint, radius * sx, point(x, y), style = Stroke(width))
        }
        fun arc(left: Float, top: Float, right: Float, bottom: Float, start: Float, sweep: Float, width: Float = sw) {
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = point(left, top),
                size = androidx.compose.ui.geometry.Size((right - left) * sx, (bottom - top) * sy),
                style = Stroke(width = width, cap = StrokeCap.Round)
            )
        }
        when (glyph) {
            OracleGlyph.Menu -> {
                line(4f, 6.5f, 20f, 6.5f)
                line(4f, 12f, 16f, 12f)
                line(4f, 17.5f, 20f, 17.5f)
            }
            OracleGlyph.NewChat -> {
                val path = Path().apply {
                    moveTo(13.7f * sx, 5f * sy)
                    lineTo(19f * sx, 10.3f * sy)
                    lineTo(9f * sx, 20f * sy)
                    lineTo(4.5f * sx, 20f * sy)
                    lineTo(4.5f * sx, 15.5f * sy)
                    close()
                }
                drawPath(path, tint, style = Stroke(sw, cap = StrokeCap.Round))
                line(12.2f, 6.5f, 17.5f, 11.8f)
            }
            OracleGlyph.Library -> {
                val p = Path().apply {
                    moveTo(3.5f * sx, 7f * sy)
                    lineTo(10f * sx, 7f * sy)
                    lineTo(12f * sx, 9.2f * sy)
                    lineTo(20.5f * sx, 9.2f * sy)
                    lineTo(20.5f * sx, 18.5f * sy)
                    lineTo(3.5f * sx, 18.5f * sy)
                    close()
                }
                drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round))
                line(3.5f, 7f, 3.5f, 5f)
                line(3.5f, 5f, 9.5f, 5f)
            }
            OracleGlyph.Settings -> {
                circle(12f, 12f, 7f)
                circle(12f, 12f, 2.3f)
                for (angle in 0..7) {
                    val radians = Math.toRadians(angle * 45.0)
                    val inner = 8.3f
                    val outer = 10.5f
                    line(
                        12f + kotlin.math.cos(radians).toFloat() * inner,
                        12f + kotlin.math.sin(radians).toFloat() * inner,
                        12f + kotlin.math.cos(radians).toFloat() * outer,
                        12f + kotlin.math.sin(radians).toFloat() * outer
                    )
                }
            }
            OracleGlyph.Back -> {
                line(19f, 12f, 5f, 12f)
                line(5f, 12f, 11f, 6f)
                line(5f, 12f, 11f, 18f)
            }
            OracleGlyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            OracleGlyph.ArrowUp -> {
                line(12f, 19f, 12f, 5f, sw * 1.15f)
                line(12f, 5f, 6.5f, 10.5f, sw * 1.15f)
                line(12f, 5f, 17.5f, 10.5f, sw * 1.15f)
            }
            OracleGlyph.ChevronDown -> {
                line(6.5f, 9.5f, 12f, 15f)
                line(12f, 15f, 17.5f, 9.5f)
            }
            OracleGlyph.Search -> {
                circle(10.5f, 10.5f, 6.2f)
                line(15f, 15f, 20f, 20f)
            }
            OracleGlyph.Spark -> {
                line(12f, 2.8f, 12f, 8.2f)
                line(12f, 15.8f, 12f, 21.2f)
                line(2.8f, 12f, 8.2f, 12f)
                line(15.8f, 12f, 21.2f, 12f)
                line(5.5f, 5.5f, 8.7f, 8.7f, sw * 0.72f)
                line(15.3f, 15.3f, 18.5f, 18.5f, sw * 0.72f)
                line(18.5f, 5.5f, 15.3f, 8.7f, sw * 0.72f)
                line(8.7f, 15.3f, 5.5f, 18.5f, sw * 0.72f)
            }
            OracleGlyph.Chat -> {
                val p = Path().apply {
                    moveTo(4f * sx, 5f * sy)
                    quadraticTo(4f * sx, 3.8f * sy, 5.4f * sx, 3.8f * sy)
                    lineTo(18.6f * sx, 3.8f * sy)
                    quadraticTo(20f * sx, 3.8f * sy, 20f * sx, 5.2f * sy)
                    lineTo(20f * sx, 15.5f * sy)
                    quadraticTo(20f * sx, 17f * sy, 18.5f * sx, 17f * sy)
                    lineTo(10f * sx, 17f * sy)
                    lineTo(5f * sx, 21f * sy)
                    lineTo(5f * sx, 17f * sy)
                    quadraticTo(4f * sx, 16.5f * sy, 4f * sx, 15f * sy)
                    close()
                }
                drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round))
                line(8f, 9f, 16f, 9f, sw * 0.72f)
                line(8f, 12.5f, 13.5f, 12.5f, sw * 0.72f)
            }
            OracleGlyph.Refresh -> {
                arc(4f, 4f, 20f, 20f, 205f, 235f)
                line(4.2f, 7.2f, 4.4f, 3.9f)
                line(4.4f, 3.9f, 7.8f, 4.5f)
                line(19.8f, 16.8f, 19.6f, 20.1f)
                line(19.6f, 20.1f, 16.2f, 19.5f)
            }
            OracleGlyph.More -> {
                circle(5f, 12f, 0.8f, sw * 1.5f)
                circle(12f, 12f, 0.8f, sw * 1.5f)
                circle(19f, 12f, 0.8f, sw * 1.5f)
            }
            OracleGlyph.Key -> {
                circle(8f, 10f, 4.2f)
                line(11.2f, 13f, 20f, 21f)
                line(16f, 17f, 18.5f, 14.5f)
                line(18f, 19f, 20.5f, 16.5f)
            }
            OracleGlyph.Eye -> {
                val p = Path().apply {
                    moveTo(2.5f * sx, 12f * sy)
                    quadraticTo(12f * sx, 2f * sy, 21.5f * sx, 12f * sy)
                    quadraticTo(12f * sx, 22f * sy, 2.5f * sx, 12f * sy)
                }
                drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round))
                circle(12f, 12f, 3f, sw * 0.9f)
            }
            OracleGlyph.EyeOff -> {
                line(3f, 3f, 21f, 21f)
                line(6.5f, 7f, 3f, 12f)
                line(3f, 12f, 6f, 16f)
                line(6f, 16f, 11f, 19f)
                line(11f, 19f, 16.5f, 17f)
                line(17.5f, 7f, 21f, 12f)
                line(21f, 12f, 18.5f, 15f)
            }
            OracleGlyph.Check -> {
                line(4.5f, 12.5f, 9.5f, 17.5f, sw * 1.25f)
                line(9.5f, 17.5f, 20f, 6.5f, sw * 1.25f)
            }
            OracleGlyph.Shield -> {
                val p = Path().apply {
                    moveTo(12f * sx, 2.8f * sy)
                    lineTo(19.5f * sx, 5.8f * sy)
                    lineTo(18.5f * sx, 14f * sy)
                    quadraticTo(17f * sx, 18.5f * sy, 12f * sx, 21f * sy)
                    quadraticTo(7f * sx, 18.5f * sy, 5.5f * sx, 14f * sy)
                    lineTo(4.5f * sx, 5.8f * sy)
                    close()
                }
                drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round))
            }
            OracleGlyph.Trash -> {
                line(4f, 6.5f, 20f, 6.5f)
                line(9f, 3.5f, 15f, 3.5f)
                line(6.5f, 6.5f, 7.5f, 20f)
                line(17.5f, 6.5f, 16.5f, 20f)
                line(7.5f, 20f, 16.5f, 20f)
                line(10f, 10f, 10.5f, 17f, sw * 0.72f)
                line(14f, 10f, 13.5f, 17f, sw * 0.72f)
            }
            OracleGlyph.Audio -> {
                line(12f, 3f, 12f, 14f, sw * 1.25f)
                arc(7f, 8f, 17f, 18f, 0f, 180f, sw * 1.1f)
                line(12f, 18f, 12f, 21f)
                line(8f, 21f, 16f, 21f)
            }
            OracleGlyph.Speaker, OracleGlyph.SpeakerOff -> {
                val speaker = androidx.compose.ui.graphics.Path().apply {
                    moveTo(3.5f * sx, 9f * sy)
                    lineTo(7.5f * sx, 9f * sy)
                    lineTo(12.5f * sx, 5f * sy)
                    lineTo(12.5f * sx, 19f * sy)
                    lineTo(7.5f * sx, 15f * sy)
                    lineTo(3.5f * sx, 15f * sy)
                    close()
                }
                drawPath(speaker, tint, style = Stroke(sw, cap = StrokeCap.Round))
                if (glyph == OracleGlyph.Speaker) {
                    arc(10f, 5f, 19f, 19f, -48f, 96f, sw)
                    arc(10f, 1.5f, 23f, 22.5f, -48f, 96f, sw)
                } else {
                    line(15f, 8f, 21f, 16f, sw * 1.15f)
                    line(21f, 8f, 15f, 16f, sw * 1.15f)
                }
            }
            OracleGlyph.AddCircle -> {
                circle(12f, 12f, 9f, sw)
                line(12f, 7f, 12f, 17f)
                line(7f, 12f, 17f, 12f)
            }
        }
    }
}

@Composable
fun OracleIconButton(
    glyph: OracleGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalOraclePalette.current.textSecondary,
    size: androidx.compose.ui.unit.Dp = 42.dp
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 550f),
        label = "icon-press"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
            .clip(CircleShape)
            .background(if (pressed) palette.surfaceRaised else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        OracleGlyphIcon(glyph, Modifier.size(20.dp), tint)
    }
}

@Composable
fun OracleTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 550f), label = "text-action")
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) palette.surfaceRaised else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            label,
            color = when {
                !enabled -> palette.textTertiary
                accent -> palette.accent
                else -> palette.textSecondary
            },
            fontFamily = OracleMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun OracleSolidAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 550f), label = "solid-action")
    Box(
        modifier = modifier
            .heightIn(min = 42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) palette.accent else palette.surfaceRaised)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            label,
            color = if (enabled) palette.onAccent else palette.textTertiary,
            fontFamily = OracleMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
fun OracleWhaleMark(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4F78E8)
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.10f, h * 0.42f)
            cubicTo(w * 0.17f, h * 0.16f, w * 0.43f, h * 0.08f, w * 0.66f, h * 0.22f)
            cubicTo(w * 0.73f, h * 0.15f, w * 0.70f, h * 0.04f, w * 0.81f, h * 0.08f)
            cubicTo(w * 0.88f, h * 0.13f, w * 0.88f, h * 0.22f, w * 0.79f, h * 0.29f)
            cubicTo(w * 0.91f, h * 0.30f, w * 0.98f, h * 0.25f, w * 0.98f, h * 0.18f)
            cubicTo(w * 1.00f, h * 0.33f, w * 0.92f, h * 0.43f, w * 0.80f, h * 0.42f)
            cubicTo(w * 0.80f, h * 0.64f, w * 0.68f, h * 0.83f, w * 0.47f, h * 0.86f)
            cubicTo(w * 0.28f, h * 0.88f, w * 0.12f, h * 0.76f, w * 0.08f, h * 0.59f)
            cubicTo(w * 0.06f, h * 0.52f, w * 0.07f, h * 0.46f, w * 0.10f, h * 0.42f)
            close()
        }
        drawPath(body, color)
        val fin = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.44f, h * 0.73f)
            cubicTo(w * 0.34f, h * 0.61f, w * 0.27f, h * 0.52f, w * 0.19f, h * 0.47f)
            cubicTo(w * 0.20f, h * 0.63f, w * 0.28f, h * 0.77f, w * 0.39f, h * 0.81f)
            close()
        }
        drawPath(fin, Color.White.copy(alpha = 0.94f))
        drawCircle(Color.White, radius = w * 0.018f, center = androidx.compose.ui.geometry.Offset(w * 0.69f, h * 0.34f))
        val tailLine = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.78f, h * 0.28f)
            cubicTo(w * 0.82f, h * 0.22f, w * 0.83f, h * 0.16f, w * 0.80f, h * 0.12f)
        }
        drawPath(tailLine, color.copy(alpha = 0.9f), style = Stroke(width = w * 0.018f, cap = StrokeCap.Round))
    }
}

@Composable
fun DeepSeekInputPill(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onThinkClick: () -> Unit,
    modifier: Modifier = Modifier,
    thinkExpanded: Boolean = false,
    attachments: List<AgentAttachmentRef> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (AgentAttachmentRef) -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoiceRelease: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onVoiceHoldChanged: (Boolean) -> Unit = {},
    onCameraClick: () -> Unit = onAttachClick,
    onPhotoClick: () -> Unit = onAttachClick,
    onDocumentClick: () -> Unit = onAttachClick,
    voiceReplyEnabled: Boolean = false,
    onVoiceReplyToggle: () -> Unit = {},
    searchEnabled: Boolean = false,
    onSearchClick: () -> Unit = {}
) {
    val palette = LocalOraclePalette.current
    var attachmentTrayOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
    val listeningAction = value.isBlank() && attachments.isEmpty() && !busy
    Column(
        modifier = modifier.fillMaxWidth()
            .animateContentSize(spring(stiffness = 420f, dampingRatio = 0.9f))
    ) {
        Column(
            Modifier.fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(15.dp), clip = false)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(palette.surfaceRaised.copy(alpha = 0.98f), palette.surface)
                    )
                )
                .border(1.dp, palette.outline.copy(alpha = 0.82f), RoundedCornerShape(15.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachments.forEach { attachment ->
                        Row(
                            Modifier.clip(RoundedCornerShape(7.dp))
                                .background(palette.surfaceSunken)
                                .border(1.dp, palette.outlineSoft, RoundedCornerShape(7.dp))
                                .padding(start = 8.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                attachment.name.take(18), color = palette.textSecondary,
                                fontFamily = OracleSans, fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("×", Modifier.clickable { onRemoveAttachment(attachment) }
                                .padding(horizontal = 2.dp), color = palette.textTertiary, fontSize = 15.sp)
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp, max = 118.dp),
                textStyle = TextStyle(
                    color = palette.text, fontFamily = OracleSans,
                    fontSize = 14.sp, lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (busy) onCancel() else onSend() }
                ),
                maxLines = 5,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(
                            "Message ORACLE…", color = palette.textTertiary,
                            fontFamily = OracleSans, fontSize = 14.sp
                        )
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(31.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (thinkExpanded) palette.accentSoft else Color.Transparent)
                        .clickable(onClick = onThinkClick)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Agent", color = if (thinkExpanded) palette.accent else palette.textSecondary,
                        fontFamily = OracleSans, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    OracleGlyphIcon(OracleGlyph.ChevronDown, Modifier.size(10.dp),
                        if (thinkExpanded) palette.accent else palette.textTertiary, 1.5f)
                }
                Spacer(Modifier.width(5.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (searchEnabled) palette.accentSoft else Color.Transparent)
                        .clickable(onClick = onSearchClick)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Web", color = if (searchEnabled) palette.accent else palette.textSecondary,
                        fontFamily = OracleSans, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .clickable {
                            attachmentTrayOpen = !attachmentTrayOpen
                            if (!attachmentTrayOpen) onAttachClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (attachmentTrayOpen) "×" else "+",
                        color = palette.textSecondary, fontSize = 20.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.width(5.dp))
                var held by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Box(
                    Modifier.size(31.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (busy) palette.surfaceSunken else palette.accent)
                        .pointerInput(busy, listeningAction) {
                            detectTapGestures(
                                onPress = {
                                    held = false
                                    val released = tryAwaitRelease()
                                    if (held) {
                                        onVoiceHoldChanged(false)
                                        if (released) onVoiceRelease() else onVoiceCancel()
                                    }
                                },
                                onLongPress = {
                                    if (listeningAction) {
                                        held = true
                                        onVoiceHoldChanged(true)
                                        onVoiceClick()
                                    }
                                },
                                onTap = {
                                    when {
                                        busy -> onCancel()
                                        listeningAction -> onVoiceClick()
                                        else -> onSend()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        busy -> Text("×", color = palette.text, fontSize = 19.sp)
                        listeningAction -> OracleGlyphIcon(OracleGlyph.Audio, Modifier.size(16.dp),
                            palette.onAccent, 1.7f)
                        else -> OracleGlyphIcon(OracleGlyph.ArrowUp, Modifier.size(16.dp),
                            palette.onAccent, 1.8f)
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = attachmentTrayOpen,
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(130))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf(
                    "Camera" to onCameraClick,
                    "Photo" to onPhotoClick,
                    "File" to onDocumentClick
                ).forEach { (label, action) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(palette.surfaceRaised)
                            .border(1.dp, palette.outlineSoft, RoundedCornerShape(8.dp))
                            .clickable(onClick = action).padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = palette.textSecondary,
                            fontFamily = OracleSans, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
