package studio.oracle.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

private sealed interface RichBlock {
    data class Heading(val text: String, val level: Int) : RichBlock
    data class Paragraph(val text: String) : RichBlock
    data class Bullet(val text: String, val number: String? = null) : RichBlock
    data class Code(val language: String, val source: String) : RichBlock
    data class Flow(val nodes: List<String>) : RichBlock
    data class RemoteImage(val alt: String, val url: String) : RichBlock
    data class Quote(val text: String) : RichBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichBlock
    data class Choice(val question: String, val options: List<String>, val multiple: Boolean) : RichBlock
    data object Divider : RichBlock
}

@Composable
fun OracleRichMessage(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null,
    onSubmitText: ((String) -> Unit)? = null
) {
    val blocks = remember(markdown) { parseRichBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        blocks.forEachIndexed { blockIndex, block ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(180, delayMillis = (blockIndex.coerceAtMost(4) * 35))) +
                    expandVertically(tween(240, delayMillis = (blockIndex.coerceAtMost(4) * 35)))
            ) {
            when (block) {
                is RichBlock.Heading -> {
                    RichInlineText(
                        block.text,
                        fontSize = when (block.level) { 1 -> 20; 2 -> 18; else -> 16 },
                        weight = FontWeight.SemiBold,
                        color = LocalOraclePalette.current.text,
                        onOpenUrl = onOpenUrl
                    )
                }
                is RichBlock.Paragraph -> RichInlineText(
                    block.text, fontSize = 16, color = LocalOraclePalette.current.text, onOpenUrl = onOpenUrl
                )
                is RichBlock.Bullet -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        block.number ?: "•",
                        modifier = Modifier.widthIn(min = 17.dp).padding(top = 1.dp),
                        color = Color(0xFF7589E3), fontFamily = OracleMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    RichInlineText(block.text, modifier = Modifier.weight(1f), fontSize = 15,
                        color = LocalOraclePalette.current.text, onOpenUrl = onOpenUrl)
                }
                is RichBlock.Code -> if (block.language.equals("mermaid", true) || block.language.equals("diagram", true)) {
                    FlowArtifactCard(parseFlowNodes(block.source))
                } else CodeArtifactCard(block.language, block.source)
                is RichBlock.Flow -> FlowArtifactCard(block.nodes)
                is RichBlock.RemoteImage -> AsyncImage(
                    model = block.url, contentDescription = block.alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(13.dp))
                )
                is RichBlock.Quote -> Row(
                    Modifier.fillMaxWidth().background(LocalOraclePalette.current.surfaceRaised, RoundedCornerShape(10.dp))
                        .padding(11.dp), verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(Color(0xFF788CE5), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    RichInlineText(block.text, fontSize = 13, color = LocalOraclePalette.current.textSecondary, onOpenUrl = onOpenUrl)
                }
                is RichBlock.Table -> RichTable(block)
                is RichBlock.Choice -> OracleChoiceBlock(
                    question = block.question,
                    options = block.options,
                    multiple = block.multiple,
                    onSubmit = onSubmitText
                )
                RichBlock.Divider -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                        .background(LocalOraclePalette.current.outlineSoft)
                )
            }
            }
        }
    }
}

@Composable
private fun RichInlineText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 16,
    weight: FontWeight = FontWeight.Normal,
    color: Color,
    onOpenUrl: ((String) -> Unit)?
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, fontSize, weight, color) { inlineMarkdown(text, color) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color, fontFamily = OracleSans, fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.58f).sp, fontWeight = weight
        ),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                if (onOpenUrl != null) onOpenUrl.invoke(annotation.item)
                else runCatching { uriHandler.openUri(annotation.item) }
            }
        }
    )
}

@Composable
private fun CodeArtifactCard(language: String, source: String) {
    val palette = LocalOraclePalette.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF171D29), RoundedCornerShape(13.dp))
            .border(1.dp, Color(0xFF374156), RoundedCornerShape(13.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(Color(0xFF7F93ED), RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp))
            Text(language.ifBlank { "CODE" }.uppercase(), color = Color(0xFFB2BFEA),
                fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(source.lines().size.toString() + " lines", color = Color(0xFF768197), fontFamily = OracleMono, fontSize = 9.sp)
            Spacer(Modifier.width(9.dp))
            Text("COPY", Modifier.clickable { clipboard.setText(AnnotatedString(source)) },
                color = Color(0xFF9AABD9), fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30394B)))
        OracleSyntaxCode(
            source = source,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(13.dp)
        )
    }
}


@Composable
private fun FlowArtifactCard(nodes: List<String>) {
    val palette = LocalOraclePalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.surface).border(1.dp, palette.outlineSoft, RoundedCornerShape(14.dp))
            .padding(11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("PROCESS MAP", color = Color(0xFF788CE5), fontFamily = OracleMono,
            fontSize = 8.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold)
        nodes.take(9).forEachIndexed { index, node ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(if (index == 0 || index == nodes.lastIndex) Color(0x177B8FE8) else palette.surfaceRaised)
                    .border(1.dp, if (index == 0 || index == nodes.lastIndex) Color(0x557B8FE8) else palette.outlineSoft, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(node, color = palette.text, fontFamily = OracleSans,
                    fontWeight = FontWeight.Medium, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (index != nodes.lastIndex) Text("↓", color = Color(0xFF788CE5), fontSize = 16.sp, lineHeight = 16.sp)
        }
    }
}

private fun parseFlowNodes(source: String): List<String> {
    val labels = Regex("\\[([^]]+)]|\\(([^)]+)\\)|\\{([^}]+)}")
        .findAll(source).map { match ->
            (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value ?: "").trim()
        }.filter { it.isNotBlank() }.distinct().toList()
    return labels.ifEmpty { listOf("Diagram", "Add node labels in Mermaid square brackets") }
}

@Composable
private fun RichTable(table: RichBlock.Table) {
    val palette = LocalOraclePalette.current
    Column(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp))
            .background(palette.surface, RoundedCornerShape(12.dp))
    ) {
        Row(Modifier.background(palette.surfaceRaised, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) {
            table.header.forEach { cell ->
                Text(cell.trim(), Modifier.widthIn(min = 92.dp, max = 210.dp).padding(10.dp),
                    color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        table.rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth()) {
                table.header.indices.forEach { column ->
                    Text(row.getOrElse(column) { "" }.trim(),
                        Modifier.widthIn(min = 92.dp, max = 210.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                        color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            if (index != table.rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.outlineSoft))
        }
    }
}


@Composable
private fun OracleChoiceBlock(
    question: String,
    options: List<String>,
    multiple: Boolean,
    onSubmit: ((String) -> Unit)?
) {
    val palette = LocalOraclePalette.current
    var selected by remember(question) { androidx.compose.runtime.mutableStateOf(setOf<Int>()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.surface).border(1.dp, palette.outline, RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (multiple) "SELECT ALL THAT APPLY" else "CHOOSE ONE",
                color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text("${options.size} options", color = palette.textTertiary, fontSize = 10.sp)
        }
        Text(question, color = palette.text, fontFamily = OracleSans,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        options.forEachIndexed { index, option ->
            val active = index in selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (active) palette.accentSoft else palette.surfaceRaised)
                    .border(1.dp, if (active) palette.accent else palette.outlineSoft, RoundedCornerShape(10.dp))
                    .clickable {
                        selected = if (multiple) {
                            if (active) selected - index else selected + index
                        } else setOf(index)
                    }.padding(horizontal = 11.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (multiple) if (active) "☑" else "□" else if (active) "◉" else "○",
                    color = if (active) palette.accent else palette.textTertiary, fontSize = 17.sp)
                Spacer(Modifier.width(10.dp))
                Text(option, color = palette.text, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        androidx.compose.material3.Button(
            onClick = {
                val answer = selected.sorted().joinToString(", ") { options[it] }
                onSubmit?.invoke("My answer to \"$question\": $answer")
            },
            enabled = selected.isNotEmpty() && onSubmit != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(9.dp)
        ) {
            Text("Submit answer")
        }
        if (onSubmit == null) Text("Interactive response unavailable in this view.",
            color = palette.textTertiary, fontSize = 10.sp)
    }
}

private fun parseRichBlocks(source: String): List<RichBlock> {
    val lines = source.replace("\r\n", "\n").lines()
    val result = mutableListOf<RichBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank()) { index++; continue }
        val imageMatch = Regex("^!\\[([^]]*)]\\((https?://[^ )]+)\\)$").find(trimmed)
        if (imageMatch != null) {
            result.add(RichBlock.RemoteImage(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            index++
            continue
        }
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) { code.add(lines[index]); index++ }
            if (index < lines.size) index++
            val source = code.joinToString("\n")
            if (language.equals("oracle-choice", true)) {
                val choice = runCatching {
                    val json = org.json.JSONObject(source)
                    val question = json.getString("question")
                    val optionsJson = json.getJSONArray("options")
                    val options = (0 until optionsJson.length()).map { optionsJson.getString(it) }
                    require(question.isNotBlank() && options.size in 2..8 && options.all { it.isNotBlank() })
                    RichBlock.Choice(question, options, json.optBoolean("multiple", false))
                }.getOrNull()
                result.add(choice ?: RichBlock.Code(language, source))
            } else result.add(RichBlock.Code(language, source))
            continue
        }
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            result.add(RichBlock.Divider); index++; continue
        }
        if (trimmed.startsWith("|") && index + 1 < lines.size && lines[index + 1].contains("---")) {
            val tableLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith("|")) { tableLines.add(lines[index]); index++ }
            val cells = tableLines.map { row -> row.trim().trim('|').split("|").map { it.trim() } }
            if (cells.size >= 2) {
                result.add(RichBlock.Table(cells.first(), cells.drop(2)))
                continue
            }
        }
        val headingMatch = Regex("^(#{1,3})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            result.add(RichBlock.Heading(headingMatch.groupValues[2], headingMatch.groupValues[1].length))
            index++; continue
        }
        if (trimmed.startsWith(">")) {
            result.add(RichBlock.Quote(trimmed.removePrefix(">").trim()))
            index++; continue
        }
        val ordered = Regex("^(\\d+)[.)]\\s+(.+)$").find(trimmed)
        if (ordered != null) {
            result.add(RichBlock.Bullet(ordered.groupValues[2], ordered.groupValues[1] + "."))
            index++; continue
        }
        val bullet = Regex("^[-*+]\\s+(.+)$").find(trimmed)
        if (bullet != null) {
            result.add(RichBlock.Bullet(bullet.groupValues[1]))
            index++; continue
        }
        val paragraph = mutableListOf(trimmed)
        index++
        while (index < lines.size && lines[index].isNotBlank()) {
            val next = lines[index].trim()
            if (next.startsWith("```") || next.startsWith("#") || next.startsWith(">") ||
                Regex("^[-*+]\\s+").containsMatchIn(next) || Regex("^\\d+[.)]\\s+").containsMatchIn(next) ||
                next == "---" || next.startsWith("|")) break
            paragraph.add(next); index++
        }
        result.add(RichBlock.Paragraph(paragraph.joinToString(" ")))
    }
    return result
}

private fun inlineMarkdown(source: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val token = Regex("\\*\\*(.+?)\\*\\*|(?<!\\*)\\*([^*]+?)\\*(?!\\*)|\\x60([^\\x60]+?)\\x60|\\[([^]]+)]\\((https?://[^ )]+)\\)")
    token.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        when {
            match.groups[1] != null -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
                append(match.groupValues[1]); pop()
            }
            match.groups[2] != null -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = color))
                append(match.groupValues[2]); pop()
            }
            match.groups[3] != null -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF788CE5),
                    background = Color(0x14788CE5)))
                append(match.groupValues[3]); pop()
            }
            match.groups[4] != null -> {
                pushStringAnnotation("URL", match.groupValues[5])
                pushStyle(SpanStyle(color = Color(0xFF7288E4), textDecoration = TextDecoration.Underline))
                append(match.groupValues[4])
                pop(); pop()
            }
        }
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}

/** Syntax-colored source renderer shared by assistant code blocks and tool execution traces. */
@Composable
fun OracleSyntaxCode(source: String, modifier: Modifier = Modifier) {
    val annotated = remember(source) { colorizeSource(source) }
    Text(
        text = annotated,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Color(0xFFE1E7F2)
        )
    )
}

private fun colorizeSource(source: String): AnnotatedString = buildAnnotatedString {
    val token = Regex("(?m)(#.*$|//.*$)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|(\\b(?:def|class|return|if|elif|else|for|while|in|not|and|or|is|import|from|as|try|except|finally|with|async|await|yield|pass|break|continue|lambda|raise|new|fun|val|var|const|let|function|public|private|static|suspend|override|when|object|interface|package)\\b)|(\\b(?:True|False|None|null|true|false|undefined)\\b)|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z_0-9]*(?=\\s*\\())")
    var cursor = 0
    token.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val color = when {
            match.groups[1] != null -> Color(0xFF8190A9)
            match.groups[2] != null -> Color(0xFFA6D6A5)
            match.groups[3] != null -> Color(0xFFC6A5F7)
            match.groups[4] != null -> Color(0xFF78C6E8)
            match.groups[5] != null -> Color(0xFFE6B477)
            match.groups[6] != null -> Color(0xFF85B8F5)
            else -> Color(0xFFE1E7F2)
        }
        pushStyle(SpanStyle(color = color, fontWeight = if (match.groups[3] != null) FontWeight.SemiBold else FontWeight.Normal))
        append(match.value)
        pop()
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}
