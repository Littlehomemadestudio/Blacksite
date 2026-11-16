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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = 420f, dampingRatio = 0.88f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(25.dp), clip = false)
                .clip(RoundedCornerShape(25.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFEAEAEA), RoundedCornerShape(25.dp))
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 9.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    attachments.forEach { attachment ->
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFF4F4F4))
                                .border(1.dp, Color(0xFFEAEAEA), RoundedCornerShape(12.dp))
                                .padding(start = 9.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    attachment.mimeType.startsWith("image/") -> "▧"
                                    attachment.mimeType.startsWith("video/") -> "▶"
                                    attachment.mimeType.startsWith("audio/") -> "♫"
                                    else -> "▤"
                                },
                                color = Color(0xFF6B87D9), fontSize = 13.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                attachment.name.take(19),
                                color = Color(0xFF555555), fontFamily = OracleSans,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "×",
                                modifier = Modifier.clickable { onRemoveAttachment(attachment) }
                                    .padding(horizontal = 3.dp),
                                color = Color(0xFF888888), fontSize = 17.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp, max = 132.dp),
                textStyle = TextStyle(
                    color = Color(0xFF171717),
                    fontFamily = OracleSans,
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                ),
                cursorBrush = SolidColor(Color(0xFF5279D8)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (busy) onCancel() else onSend() }
                ),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                "Type a message or hold to speak",
                                color = Color(0xFFB3B3B3),
                                fontFamily = OracleSans,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(Modifier.height(9.dp))

            Row(
                Modifier.fillMaxWidth().height(37.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThinkPill(onClick = onThinkClick, expanded = thinkExpanded)
                Spacer(Modifier.width(7.dp))
                SearchPill(enabled = searchEnabled, onClick = onSearchClick)
                Spacer(Modifier.weight(1f))

                if (attachmentTrayOpen) {
                    RoundComposerAction(
                        symbol = "×",
                        description = "Close attachments",
                        onClick = { attachmentTrayOpen = false }
                    )
                } else {
                    RoundComposerAction(
                        symbol = "+",
                        description = "Add attachment",
                        onClick = {
                            attachmentTrayOpen = true
                            onAttachClick()
                        }
                    )
                }
                Spacer(Modifier.width(7.dp))
                OracleSendButton(
                    busy = busy,
                    listeningAction = value.isBlank() && attachments.isEmpty() && !busy,
                    onTap = {
                        when {
                            busy -> onCancel()
                            value.isBlank() && attachments.isEmpty() -> onVoiceClick()
                            else -> onSend()
                        }
                    },
                    onVoiceHoldStart = {
                        onVoiceHoldChanged(true)
                        onVoiceClick()
                    },
                    onVoiceHoldEnd = {
                        onVoiceHoldChanged(false)
                        onVoiceRelease()
                    },
                    onVoiceHoldCancel = {
                        onVoiceHoldChanged(false)
                        onVoiceCancel()
                    }
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = attachmentTrayOpen,
            enter = androidx.compose.animation.fadeIn(tween(160)) +
                androidx.compose.animation.expandVertically(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(110)) +
                androidx.compose.animation.shrinkVertically(tween(160))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 13.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttachmentChoice("Camera", "▣", onCameraClick, Modifier.weight(1f))
                AttachmentChoice("Photo", "▧", onPhotoClick, Modifier.weight(1f))
                AttachmentChoice("Document", "⌁", onDocumentClick, Modifier.weight(1f))
            }
        }


    }
}

@Composable
private fun AttachmentChoice(
    label: String,
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.height(86.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Color(0xFFF4F4F4))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(symbol, color = Color(0xFF171717), fontSize = 26.sp, lineHeight = 29.sp)
        Text(label, color = Color(0xFF171717), fontFamily = OracleSans,
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundComposerAction(
    symbol: String,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(37.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .border(1.dp, Color(0xFFE2E2E2), CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = Color(0xFF171717),
            fontFamily = OracleSans,
            fontSize = if (symbol == "+") 25.sp else 23.sp,
            lineHeight = 25.sp
        )
    }
}

@Composable
private fun ThinkPill(onClick: () -> Unit, expanded: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(stiffness = 560f, dampingRatio = 0.82f),
        label = "think-pill"
    )
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        spring(stiffness = 420f, dampingRatio = 0.82f),
        label = "think-chevron"
    )
    Row(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(if (expanded || pressed) Color(0xFFF3F3F3) else Color.White)
            .border(1.dp, Color(0xFFE9E9E9), RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleGlyphIcon(OracleGlyph.Spark, Modifier.size(14.dp), Color(0xFF181818), 1.5f)
        Spacer(Modifier.width(6.dp))
        Text("Think", color = Color(0xFF171717), fontFamily = OracleSans,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(3.dp))
        OracleGlyphIcon(
            OracleGlyph.ChevronDown,
            Modifier.size(10.dp).graphicsLayer { rotationZ = rotation },
            Color(0xFF777777),
            1.4f
        )
    }
}

@Composable
private fun SearchPill(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(22.dp))
            .background(if (enabled) Color(0xFFEAF1FF) else Color(0xFFF5F7FA))
            .border(1.dp, if (enabled) Color(0xFFD7E3FF) else Color(0xFFE8EBF0), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("◎", color = Color(0xFF4F78D8), fontSize = 15.sp)
        Spacer(Modifier.width(5.dp))
        Text("Search", color = Color(0xFF4F78D8), fontFamily = OracleSans,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OracleSendButton(
    busy: Boolean,
    listeningAction: Boolean,
    onTap: () -> Unit,
    onVoiceHoldStart: () -> Unit,
    onVoiceHoldEnd: () -> Unit,
    onVoiceHoldCancel: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(stiffness = 560f, dampingRatio = 0.8f),
        label = "composer-action-press"
    )
    var held by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = Modifier.size(39.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Color(0xFFE4E4E4), CircleShape)
            .pointerInput(busy, listeningAction) {
                detectTapGestures(
                    onPress = {
                        held = false
                        val released = tryAwaitRelease()
                        if (held) {
                            if (released) onVoiceHoldEnd() else onVoiceHoldCancel()
                        }
                    },
                    onLongPress = {
                        if (listeningAction) {
                            held = true
                            onVoiceHoldStart()
                        }
                    },
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            busy -> Text("×", color = Color(0xFF171717), fontSize = 23.sp)
            listeningAction -> OracleGlyphIcon(OracleGlyph.Audio, Modifier.size(20.dp), Color(0xFF171717), 1.7f)
            else -> OracleGlyphIcon(OracleGlyph.ArrowUp, Modifier.size(19.dp), Color(0xFF171717), 1.9f)
        }
    }
}
