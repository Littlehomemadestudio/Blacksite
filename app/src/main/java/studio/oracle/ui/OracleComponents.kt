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
    Search, Spark, Chat, Refresh, More, Key, Eye, EyeOff, Check, Shield, Trash, Audio
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
    voiceReplyEnabled: Boolean = false,
    onVoiceReplyToggle: () -> Unit = {},
    searchEnabled: Boolean = false,
    onSearchClick: () -> Unit = {}
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.86f),
        label = "composer-press"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) palette.accent.copy(alpha = 0.72f) else palette.outline,
        animationSpec = tween(220),
        label = "composer-border"
    )
    val elevation by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (focused) 5.dp else 1.dp,
        animationSpec = spring(stiffness = 360f, dampingRatio = 0.86f),
        label = "composer-elevation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = 420f, dampingRatio = 0.88f))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
             .shadow(elevation + 3.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFECECEC), RoundedCornerShape(28.dp))
            .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 8.dp)
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                attachments.forEach { attachment ->
                    Row(
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(palette.surfaceRaised)
                            .border(1.dp, palette.outlineSoft, RoundedCornerShape(11.dp))
                            .padding(start = 9.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Text(
                            when {
                                attachment.mimeType.startsWith("image/") -> "▧"
                                attachment.mimeType.startsWith("video/") -> "▶"
                                attachment.mimeType.startsWith("audio/") -> "♫"
                                else -> "▤"
                            },
                            color = palette.accent, fontSize = 13.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        androidx.compose.material3.Text(
                            attachment.name.take(19),
                            color = palette.textSecondary, fontFamily = OracleSans,
                            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(5.dp))
                        androidx.compose.material3.Text(
                            "×",
                            modifier = Modifier.clickable { onRemoveAttachment(attachment) }.padding(horizontal = 3.dp),
                            color = palette.textTertiary, fontSize = 16.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp, max = 132.dp),
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                color = palette.text,
                fontFamily = OracleSans,
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { if (busy) onCancel() else onSend() }
            ),
            interactionSource = interaction,
            maxLines = 6,
            decorationBox = { innerTextField ->
                Box(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        androidx.compose.material3.Text(
                            "Type a message or hold to speak",
                            color = palette.textTertiary,
                            fontFamily = OracleSans,
                            fontSize = 15.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkPill(onClick = onThinkClick, expanded = thinkExpanded)
            Spacer(Modifier.width(7.dp))
            SearchPill(enabled = searchEnabled, onClick = onSearchClick)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE6E6E6), CircleShape)
                    .clickable(onClick = onAttachClick),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text("＋", color = Color(0xFF171717), fontSize = 23.sp, lineHeight = 24.sp)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = busy,
                enter = androidx.compose.animation.fadeIn(tween(150)) +
                    androidx.compose.animation.expandHorizontally(tween(180)),
                exit = androidx.compose.animation.fadeOut(tween(100)) +
                    androidx.compose.animation.shrinkHorizontally(tween(150))
            ) {
                androidx.compose.material3.Text(
                    "WORKING",
                    color = palette.textTertiary,
                    fontFamily = OracleMono,
                    fontSize = 9.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            OracleSendButton(
                enabled = true,
                busy = busy,
                listeningAction = value.isBlank() && attachments.isEmpty() && !busy,
                onClick = when {
                    busy -> onCancel
                    value.isBlank() && attachments.isEmpty() -> onVoiceClick
                    else -> onSend
                }
            )
        }
    }
}

@Composable
private fun ThinkPill(onClick: () -> Unit, expanded: Boolean) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 550f), label = "think-pill")
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.82f),
        label = "think-chevron"
    )
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
            .clip(RoundedCornerShape(22.dp))
            .background(if (pressed || expanded) Color(0xFFF1F1F1) else Color.White)
            .border(1.dp, Color(0xFFE9E9E9), RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleGlyphIcon(OracleGlyph.Spark, Modifier.size(13.dp), palette.textSecondary, 1.35f)
        Spacer(Modifier.width(7.dp))
        androidx.compose.material3.Text(
            "Think",
            color = palette.textSecondary,
            fontFamily = OracleSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(4.dp))
        OracleGlyphIcon(
            OracleGlyph.ChevronDown,
            Modifier.size(11.dp).graphicsLayer { rotationZ = chevronRotation },
            palette.textTertiary,
            1.4f
        )
    }
}

@Composable
private fun SearchPill(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) Color(0xFFEAF1FF) else Color.White)
            .border(1.dp, if (enabled) Color(0xFFD8E4FF) else Color(0xFFE9E9E9), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text("◎", color = Color(0xFF4F78D8), fontSize = 14.sp)
        Spacer(Modifier.width(5.dp))
        androidx.compose.material3.Text(
            "Search",
            color = Color(0xFF4F78D8),
            fontFamily = OracleSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OracleSendButton(
    enabled: Boolean,
    busy: Boolean,
    listeningAction: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 560f, dampingRatio = 0.78f),
        label = "send-press"
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (pressed) 1.dp.toPx() else 0f
            }
            .clip(CircleShape)
            .background(if (enabled) palette.accent else palette.surfaceRaised)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = when {
                busy -> "cancel"
                listeningAction -> "voice"
                else -> "send"
            },
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(130)) +
                    androidx.compose.animation.scaleIn(initialScale = 0.72f, animationSpec = tween(170))) togetherWith
                    (androidx.compose.animation.fadeOut(tween(90)) +
                        androidx.compose.animation.scaleOut(targetScale = 0.72f, animationSpec = tween(130)))
            },
            label = "send-icon"
        ) { action ->
            when (action) {
                "cancel" -> OracleGlyphIcon(
                    OracleGlyph.Close, Modifier.size(19.dp),
                    tint = if (enabled) palette.onAccent else palette.textTertiary,
                    strokeWidth = 1.8f
                )
                "voice" -> androidx.compose.material3.Text(
                    "🎙", fontSize = 18.sp,
                    color = if (enabled) palette.onAccent else palette.textTertiary
                )
                else -> OracleGlyphIcon(
                    OracleGlyph.ArrowUp, Modifier.size(19.dp),
                    tint = if (enabled) palette.onAccent else palette.textTertiary,
                    strokeWidth = 1.8f
                )
            }
        }
    }
}
