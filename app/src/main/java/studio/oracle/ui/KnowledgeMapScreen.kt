package studio.oracle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import studio.oracle.data.KnowledgeGraphRepository
import studio.oracle.data.KnowledgeGraphSnapshot
import studio.oracle.data.local.DocumentEntity
import studio.oracle.data.local.KnowledgeConceptEntity
import studio.oracle.data.local.KnowledgeEdgeEntity
import studio.oracle.data.local.KnowledgeMentionEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun KnowledgeMapScreen(
    workspaceId: String,
    documents: List<DocumentEntity>,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalOraclePalette.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database, workspaceId) {
        KnowledgeGraphRepository(database.documentDao(), database.knowledgeGraphDao(), workspaceId)
    }
    val scope = rememberCoroutineScope()
    val documentsById = remember(documents) { documents.associateBy { it.id } }

    var snapshot by remember { mutableStateOf(KnowledgeGraphSnapshot(emptyList(), emptyList())) }
    var selectedConceptId by remember { mutableStateOf<String?>(null) }
    var mentions by remember { mutableStateOf(emptyList<KnowledgeMentionEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var rebuilding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("LOADING LOCAL MAP") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspaceId) {
        loading = true
        try {
            snapshot = repository.snapshot()
            selectedConceptId = snapshot.concepts.firstOrNull()?.id
            status = if (snapshot.concepts.isEmpty()) "MAP NOT BUILT" else "LOCAL MAP READY"
        } catch (_: Exception) {
            error = "Could not load the local knowledge map."
            status = "MAP LOAD FAILED"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedConceptId, workspaceId) {
        mentions = selectedConceptId?.let { repository.mentionsForConcept(it) }.orEmpty()
    }

    fun rebuildMap() {
        if (rebuilding) return
        scope.launch {
            rebuilding = true
            error = null
            status = "ANALYSING INDEXED PASSAGES"
            try {
                val result = repository.rebuild()
                snapshot = repository.snapshot()
                selectedConceptId = snapshot.concepts.firstOrNull()?.id
                status = "MAP READY · ${result.conceptCount} CONCEPTS · ${result.edgeCount} LINKS"
                if (result.truncated) {
                    error = "The map reached its local processing limit. It includes the newest indexed passages only."
                } else if (result.conceptCount == 0) {
                    status = "NO REPEATED CONCEPTS FOUND"
                }
            } catch (_: Exception) {
                error = "Could not rebuild the local map. Your indexed documents were not changed."
                status = "MAP BUILD FAILED"
            } finally {
                rebuilding = false
            }
        }
    }

    val selectedConcept = snapshot.concepts.firstOrNull { it.id == selectedConceptId }
    val conceptsById = remember(snapshot.concepts) { snapshot.concepts.associateBy { it.id } }
    val degreeById = remember(snapshot.edges) {
        buildMap<String, Int> {
            snapshot.edges.forEach { edge ->
                put(edge.sourceConceptId, (get(edge.sourceConceptId) ?: 0) + edge.weight)
                put(edge.targetConceptId, (get(edge.targetConceptId) ?: 0) + edge.weight)
            }
        }
    }
    val relatedConcepts = remember(snapshot, selectedConceptId) {
        snapshot.edges.asSequence()
            .filter { it.sourceConceptId == selectedConceptId || it.targetConceptId == selectedConceptId }
            .sortedByDescending { it.weight }
            .mapNotNull { edge ->
                conceptsById[if (edge.sourceConceptId == selectedConceptId) edge.targetConceptId else edge.sourceConceptId]
            }
            .distinctBy { it.id }
            .take(12)
            .toList()
    }

    Column(
        Modifier.fillMaxSize().background(palette.canvas).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OracleIconButton(OracleGlyph.Back, "Back to library", onClick = onBack, tint = palette.accent, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("KNOWLEDGE MAP", color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.3.sp)
                Text("Connections across your library", color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            OracleSolidAction(
                label = if (rebuilding) "BUILDING…" else "REBUILD",
                onClick = { rebuildMap() },
                enabled = !rebuilding && !loading && documents.isNotEmpty()
            )
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surface)
                .border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("OFFLINE · LEXICAL CO-OCCURRENCE · NO EMBEDDINGS", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp)
            Text(status, color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OracleSpinner(tint = palette.accent)
                }
            }
            documents.isEmpty() -> {
                KnowledgeCard {
                    Text("YOUR LIBRARY IS EMPTY", color = palette.text, fontFamily = OracleMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Import documents first. ORACLE builds this map entirely on-device from indexed passages.", color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            snapshot.concepts.isEmpty() -> {
                KnowledgeCard {
                    Text("BUILD YOUR LOCAL KNOWLEDGE MAP", color = palette.text, fontFamily = OracleMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Repeated terms and phrases are connected when they appear in the same indexed passage. This is a transparent lexical map—not an AI-generated or semantic graph. Your documents stay on this device.",
                        color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    OracleSolidAction("BUILD MAP", onClick = { rebuildMap() }, enabled = !rebuilding)
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MetricCard("CONCEPTS", snapshot.concepts.size.toString(), Modifier.weight(1f))
                    MetricCard("LINKS", snapshot.edges.size.toString(), Modifier.weight(1f))
                    MetricCard("SOURCES", documents.size.toString(), Modifier.weight(1f))
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text("CONCEPT NETWORK", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.1.sp)
                        KnowledgeNetwork(
                            concepts = snapshot.concepts.sortedWith(
                                compareByDescending<KnowledgeConceptEntity> { degreeById[it.id] ?: 0 }
                                    .thenByDescending { it.documentCount }
                                    .thenBy { it.label }
                            ),
                            edges = snapshot.edges,
                            selectedConceptId = selectedConceptId,
                            onSelect = { selectedConceptId = it }
                        )
                        Text("SELECTED CONCEPT", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.1.sp)
                    }
                    if (selectedConcept != null) {
                        item {
                            KnowledgeCard {
                                Text(selectedConcept.label, color = palette.text, fontFamily = OracleSans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${selectedConcept.occurrenceCount} occurrences · ${selectedConcept.documentCount} source documents · ${degreeById[selectedConcept.id] ?: 0} connection weight",
                                    color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp
                                )
                                if (relatedConcepts.isNotEmpty()) {
                                    Text("CO-OCCURS WITH", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.sp)
                                    relatedConcepts.forEach { related ->
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                                .background(palette.surfaceRaised).clickable { selectedConceptId = related.id }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(related.label, color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                            Text("${degreeById[related.id] ?: 0}", color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Text("SOURCE PASSAGES", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.1.sp)
                        }
                        if (mentions.isEmpty()) {
                            item { Text("No source passages are available for this concept.", color = palette.textSecondary, fontSize = 10.sp) }
                        } else {
                            items(mentions, key = { "mention-${it.id}" }) { mention ->
                                val source = documentsById[mention.documentId]
                                KnowledgeCard(
                                    onClick = { if (source != null) onOpenDocument(source.id) }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(source?.title ?: "Source document", color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Text(mention.sourceLabel, color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
                                        }
                                        Text("OPEN", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp)
                                    }
                                    Text(mention.excerpt, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
                                }
                            }
                        }
                    }
                    item {
                        Text("ALL CONCEPTS", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.1.sp)
                    }
                    items(snapshot.concepts, key = { "concept-${it.id}" }) { concept ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                                .background(if (concept.id == selectedConceptId) palette.accentSoft else palette.surface)
                                .border(1.dp, if (concept.id == selectedConceptId) palette.accent.copy(alpha = 0.5f) else palette.outlineSoft, RoundedCornerShape(11.dp))
                                .clickable { selectedConceptId = concept.id }
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(concept.label, color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${concept.documentCount} DOC · ${concept.occurrenceCount}×", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
        if (error != null) {
            Text(error!!, color = palette.warning, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun KnowledgeNetwork(
    concepts: List<KnowledgeConceptEntity>,
    edges: List<KnowledgeEdgeEntity>,
    selectedConceptId: String?,
    onSelect: (String) -> Unit
) {
    val palette = LocalOraclePalette.current
    val density = LocalDensity.current
    val nodes = concepts.take(8)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(218.dp).clip(RoundedCornerShape(15.dp))
            .background(palette.surfaceSunken).border(1.dp, palette.outlineSoft, RoundedCornerShape(15.dp))
    ) {
        val positions = remember(nodes, maxWidth, maxHeight, density) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val radiusX = (widthPx / 2f - with(density) { 40.dp.toPx() }).coerceAtLeast(0f)
            val radiusY = (heightPx / 2f - with(density) { 23.dp.toPx() }).coerceAtLeast(0f)
            nodes.mapIndexed { index, _ ->
                val angle = if (nodes.size <= 1) -Math.PI / 2.0
                else -Math.PI / 2.0 + (2.0 * Math.PI * index / nodes.size)
                Offset(
                    x = widthPx / 2f + kotlin.math.cos(angle).toFloat() * radiusX,
                    y = heightPx / 2f + kotlin.math.sin(angle).toFloat() * radiusY
                )
            }
        }
        val positionsById = remember(nodes, positions) { nodes.mapIndexed { index, node -> node.id to positions[index] }.toMap() }
        Canvas(Modifier.fillMaxSize()) {
            edges.forEach { edge ->
                val start = positionsById[edge.sourceConceptId] ?: return@forEach
                val end = positionsById[edge.targetConceptId] ?: return@forEach
                drawLine(
                    color = palette.accent.copy(alpha = (0.16f + edge.weight.coerceAtMost(8) * 0.045f).coerceAtMost(0.52f)),
                    start = start,
                    end = end,
                    strokeWidth = (0.8f + edge.weight.coerceAtMost(5) * 0.25f).dp.toPx()
                )
            }
            nodes.forEachIndexed { index, node ->
                drawCircle(
                    color = if (node.id == selectedConceptId) palette.accent.copy(alpha = 0.25f) else palette.surfaceRaised,
                    radius = 17.dp.toPx(),
                    center = positions[index]
                )
                drawCircle(
                    color = if (node.id == selectedConceptId) palette.accent else palette.outline,
                    radius = 17.dp.toPx(),
                    center = positions[index],
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                )
            }
        }
        nodes.forEachIndexed { index, node ->
            val point = positions[index]
            val x = with(density) { point.x.toDp() } - 36.dp
            val y = with(density) { point.y.toDp() } - 13.dp
            Box(
                Modifier.offset(x = x, y = y).width(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (node.id == selectedConceptId) palette.accentSoft else palette.surface)
                    .border(1.dp, if (node.id == selectedConceptId) palette.accent else palette.outline, RoundedCornerShape(8.dp))
                    .clickable { onSelect(node.id) }
                    .padding(horizontal = 5.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(node.label, color = palette.text, fontFamily = OracleSans, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalOraclePalette.current
    Column(
        modifier.clip(RoundedCornerShape(11.dp)).background(palette.surface)
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(11.dp)).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = palette.textSecondary, fontFamily = OracleMono, fontSize = 7.sp, letterSpacing = 0.8.sp)
        Text(value, color = palette.text, fontFamily = OracleSans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun KnowledgeCard(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalOraclePalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(palette.surface)
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(13.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content
    )
}
