package studio.oracle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.oracle.data.DocumentRepository
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans
import studio.oracle.data.LocalWorkflowRunner
import studio.oracle.data.WorkflowAction
import studio.oracle.data.WorkflowRepository
import studio.oracle.data.WorkflowRules
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunLogEntity
import studio.oracle.data.local.WorkflowStepEntity
import java.util.UUID

@Composable
fun WorkflowScreen(workspaceId: String) {
    val context = LocalContext.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database) { WorkflowRepository(database.workflowDao()) }
    val documentRepository = remember(context, database) {
        DocumentRepository(context, database.documentDao(), workspaceId)
    }
    val runner = remember(database, documentRepository) {
        LocalWorkflowRunner(database.workflowDao(), documentRepository)
    }
    val scope = rememberCoroutineScope()
    val workflows by repository.workflows.collectAsState(initial = emptyList())
    val notes by repository.notes.collectAsState(initial = emptyList())
    val runs by repository.runs.collectAsState(initial = emptyList())

    var tab by remember { mutableStateOf("WORKFLOWS") }
    var selectedWorkflowId by remember { mutableStateOf<String?>(null) }
    var loadedWorkflowId by remember { mutableStateOf<String?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftDescription by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf(emptyList<WorkflowStepEntity>()) }
    var status by remember { mutableStateOf("LOCAL WORKSPACE · MANUAL TRIGGERS ONLY") }
    var saving by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var showRunConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var viewingRunId by remember { mutableStateOf<String?>(null) }
    var viewingLogs by remember { mutableStateOf(emptyList<WorkflowRunLogEntity>()) }
    var viewingRunTitle by remember { mutableStateOf("") }

    val selectedWorkflow = workflows.firstOrNull { it.id == selectedWorkflowId }

    LaunchedEffect(selectedWorkflowId, workflows) {
        val id = selectedWorkflowId
        if (id == null) {
            loadedWorkflowId = null
            steps = emptyList()
            return@LaunchedEffect
        }
        if (loadedWorkflowId != id) {
            val workflow = workflows.firstOrNull { it.id == id } ?: return@LaunchedEffect
            draftTitle = workflow.title
            draftDescription = workflow.description
            steps = repository.stepsFor(id)
            loadedWorkflowId = id
        }
    }

    LaunchedEffect(viewingRunId) {
        val id = viewingRunId
        viewingLogs = if (id == null) emptyList() else database.workflowDao().logsForRun(id)
    }

    BackHandler(enabled = selectedWorkflowId != null && tab == "WORKFLOWS") {
        selectedWorkflowId = null
    }

    fun updateStep(index: Int, updated: WorkflowStepEntity) {
        steps = steps.toMutableList().also { list -> list[index] = updated }
    }

    fun moveStep(index: Int, delta: Int) {
        val target = index + delta
        if (target !in steps.indices) return
        steps = steps.toMutableList().also { list ->
            val moved = list.removeAt(index)
            list.add(target, moved)
            for (i in list.indices) list[i] = list[i].copy(position = i)
        }
    }

    fun persistWorkflow(): String? {
        val workflow = selectedWorkflow ?: return "Select a workflow first."
        val validation = WorkflowRules.validate(steps)
        if (validation != null) return validation
        scope.launch {
            saving = true
            try {
                repository.save(
                    workflow.copy(
                        title = draftTitle.trim().ifBlank { "Untitled workflow" },
                        description = draftDescription.trim()
                    ),
                    steps
                )
                status = "SAVED LOCALLY · ${steps.size} STEPS"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "SAVE FAILED · CHECK LOCAL STORAGE"
            } finally {
                saving = false
            }
        }
        return null
    }

    Column(
        Modifier.fillMaxSize().background(LocalOraclePalette.current.canvas).imePadding().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AUTOMATION STUDIO", color = LocalOraclePalette.current.accent, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Text("Workflows", color = LocalOraclePalette.current.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Local actions · explicit manual runs", color = LocalOraclePalette.current.textSecondary, fontSize = 10.sp)
            }
            if (tab == "WORKFLOWS" && selectedWorkflowId == null) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val created = repository.create()
                                selectedWorkflowId = created.id
                                status = "NEW WORKFLOW · ADD A STEP"
                            } catch (_: Exception) {
                                status = "COULD NOT CREATE WORKFLOW"
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalOraclePalette.current.accent, contentColor = LocalOraclePalette.current.canvas)
                ) {
                    OracleGlyphIcon(OracleGlyph.NewChat, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                    Spacer(Modifier.width(5.dp))
                    Text("New", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(LocalOraclePalette.current.surface, RoundedCornerShape(14.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FlowTab("Workflows", tab == "WORKFLOWS", Modifier.weight(1f)) {
                tab = "WORKFLOWS"
                selectedWorkflowId = null
            }
            FlowTab("Notes", tab == "NOTES", Modifier.weight(1f)) { tab = "NOTES" }
            FlowTab("History", tab == "HISTORY", Modifier.weight(1f)) { tab = "HISTORY" }
        }

        when (tab) {
            "NOTES" -> {
                if (notes.isEmpty()) {
                    FlowEmptyState("No local notes yet", "A workflow can create notes or append to existing ones. Notes stay on this device.")
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(notes, key = { it.id }) { note ->
                            Surface(color = LocalOraclePalette.current.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(note.title, color = LocalOraclePalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(note.content, color = LocalOraclePalette.current.textSecondary, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    Text("${note.content.length} CHARACTERS · LOCAL NOTE", color = LocalOraclePalette.current.accent, fontSize = 8.sp, letterSpacing = .8.sp)
                                }
                            }
                        }
                    }
                }
            }

            "HISTORY" -> {
                if (runs.isEmpty()) {
                    FlowEmptyState("No runs recorded", "Run a saved workflow to create an auditable execution record.")
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(runs, key = { it.id }) { run ->
                            val name = workflows.firstOrNull { it.id == run.workflowId }?.title ?: "Deleted workflow"
                            Surface(
                                color = LocalOraclePalette.current.surface,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewingRunId = run.id
                                    viewingRunTitle = name
                                }
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(name, color = LocalOraclePalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        RunStatus(run.status)
                                    }
                                    Text(run.summary, color = LocalOraclePalette.current.textSecondary, fontSize = 10.sp)
                                    Text("TAP TO INSPECT EXECUTION LOG", color = LocalOraclePalette.current.accent, fontSize = 8.sp, letterSpacing = .8.sp)
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                if (selectedWorkflowId == null) {
                    if (workflows.isEmpty()) {
                        FlowEmptyState("Build your first workflow", "Combine local note actions and document searches. Nothing runs until you review and confirm it.")
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(workflows, key = { it.id }) { workflow ->
                                Surface(
                                    color = LocalOraclePalette.current.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedWorkflowId = workflow.id
                                        status = "EDITING · ${workflow.title}"
                                    }
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(13.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OracleGlyphIcon(OracleGlyph.Settings, Modifier.size(20.dp), LocalOraclePalette.current.textSecondary)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(workflow.title, color = LocalOraclePalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(workflow.description.ifBlank { "Manual workflow · local only" }, color = LocalOraclePalette.current.textSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        OracleGlyphIcon(OracleGlyph.Back, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OracleIconButton(OracleGlyph.Back, "Back to workflows", onClick = { selectedWorkflowId = null }, tint = LocalOraclePalette.current.accent, size = 34.dp)
                        Column(Modifier.weight(1f)) {
                            Text("WORKFLOW BUILDER", color = LocalOraclePalette.current.accent, fontSize = 9.sp, letterSpacing = 1.2.sp)
                            Text("Edit steps", color = LocalOraclePalette.current.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("${steps.size}/${WorkflowRules.MAX_STEPS}", color = LocalOraclePalette.current.textSecondary, fontSize = 10.sp)
                    }

                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 5.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                FlowField(
                                    value = draftTitle,
                                    onValueChange = { draftTitle = it.take(WorkflowRules.MAX_TITLE_CHARS) },
                                    placeholder = "Workflow name",
                                    singleLine = true
                                )
                                FlowField(
                                    value = draftDescription,
                                    onValueChange = { draftDescription = it.take(500) },
                                    placeholder = "What should this workflow do? (optional)",
                                    singleLine = false,
                                    minLines = 2
                                )
                                Surface(color = LocalOraclePalette.current.surfaceRaised, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft)) {
                                    Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OracleGlyphIcon(OracleGlyph.Settings, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                                        Spacer(Modifier.width(7.dp))
                                        Text("MANUAL TRIGGER  ·  ON-DEVICE EXECUTION  ·  NO NETWORK", color = LocalOraclePalette.current.textSecondary, fontSize = 8.sp, letterSpacing = .35.sp)
                                    }
                                }
                            }
                        }
                        itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                            WorkflowStepCard(
                                index = index,
                                step = step,
                                onChange = { updateStep(index, it) },
                                onMoveUp = { moveStep(index, -1) },
                                onMoveDown = { moveStep(index, 1) },
                                onRemove = { steps = steps.filterNot { it.id == step.id } }
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = {
                                    val id = selectedWorkflowId ?: return@OutlinedButton
                                    if (steps.size >= WorkflowRules.MAX_STEPS) {
                                        status = "MAXIMUM ${WorkflowRules.MAX_STEPS} STEPS"
                                    } else {
                                        steps = steps + WorkflowStepEntity(
                                            id = UUID.randomUUID().toString(),
                                            workflowId = id,
                                            position = steps.size,
                                            actionType = WorkflowAction.CREATE_NOTE.key,
                                            title = "",
                                            input = ""
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalOraclePalette.current.accent)
                            ) {
                                OracleGlyphIcon(OracleGlyph.NewChat, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text("Add step", fontSize = 12.sp)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalOraclePalette.current.error)
                        ) {
                            OracleGlyphIcon(OracleGlyph.Trash, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                val issue = WorkflowRules.validate(steps)
                                if (issue != null) status = issue else {
                                    val workflow = selectedWorkflow
                                    if (workflow == null) status = "WORKFLOW NOT READY"
                                    else scope.launch {
                                        saving = true
                                        try {
                                            repository.save(workflow.copy(title = draftTitle, description = draftDescription), steps)
                                            status = "SAVED LOCALLY"
                                        } catch (_: Exception) {
                                            status = "SAVE FAILED"
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            },
                            enabled = !saving && !running,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 9.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalOraclePalette.current.text)
                        ) {
                            OracleGlyphIcon(OracleGlyph.Check, Modifier.size(15.dp), LocalOraclePalette.current.textSecondary)
                            Spacer(Modifier.width(5.dp))
                            Text("Save", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val issue = WorkflowRules.validate(steps)
                                if (issue != null) status = issue else showRunConfirmation = true
                            },
                            enabled = !saving && !running && selectedWorkflow != null,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalOraclePalette.current.accent, contentColor = LocalOraclePalette.current.canvas)
                        ) {
                            if (running) CircularProgressIndicator(Modifier.size(14.dp), color = LocalOraclePalette.current.canvas, strokeWidth = 2.dp)
                            else OracleGlyphIcon(OracleGlyph.ArrowUp, Modifier.size(16.dp), LocalOraclePalette.current.textSecondary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (running) "Running" else "Run", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Text(status, color = LocalOraclePalette.current.textSecondary, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    if (showRunConfirmation) {
        AlertDialog(
            onDismissRequest = { showRunConfirmation = false },
            containerColor = LocalOraclePalette.current.surface,
            titleContentColor = LocalOraclePalette.current.text,
            textContentColor = LocalOraclePalette.current.textSecondary,
            title = { Text("Run this workflow locally?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${steps.size} ordered step(s) will run after your confirmation.")
                    steps.sortedBy { it.position }.forEachIndexed { index, step ->
                        val action = WorkflowAction.from(step.actionType)
                        Text("${index + 1}. ${action?.label ?: "Unknown action"} — ${step.title}", color = LocalOraclePalette.current.text, fontSize = 11.sp)
                    }
                    Text("No Gemini request, network access, app launch, file sharing or scheduled trigger is involved.", color = LocalOraclePalette.current.accent, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRunConfirmation = false
                    val workflow = selectedWorkflow ?: return@TextButton
                    scope.launch {
                        running = true
                        try {
                            repository.save(workflow.copy(title = draftTitle, description = draftDescription), steps)
                            val result = runner.execute(
                                workflow.copy(title = draftTitle.trim().ifBlank { "Untitled workflow" }),
                                steps
                            )
                            status = result.summary
                            if (result.status == "SUCCEEDED") tab = "HISTORY"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "RUN FAILED · SEE EXECUTION HISTORY"
                        } finally {
                            running = false
                        }
                    }
                }) { Text("Confirm run", color = LocalOraclePalette.current.accent) }
            },
            dismissButton = { TextButton(onClick = { showRunConfirmation = false }) { Text("Cancel", color = LocalOraclePalette.current.textSecondary) } }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = LocalOraclePalette.current.surface,
            titleContentColor = LocalOraclePalette.current.text,
            textContentColor = LocalOraclePalette.current.textSecondary,
            title = { Text("Delete workflow?") },
            text = { Text("This permanently deletes the workflow, its steps and its run history. Local notes created by previous runs are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = selectedWorkflowId
                    showDeleteConfirmation = false
                    if (id != null) scope.launch {
                        repository.delete(id)
                        selectedWorkflowId = null
                        status = "WORKFLOW DELETED · NOTES PRESERVED"
                    }
                }) { Text("Delete", color = LocalOraclePalette.current.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel", color = LocalOraclePalette.current.textSecondary) } }
        )
    }

    if (viewingRunId != null) {
        AlertDialog(
            onDismissRequest = { viewingRunId = null },
            containerColor = LocalOraclePalette.current.surface,
            titleContentColor = LocalOraclePalette.current.text,
            textContentColor = LocalOraclePalette.current.textSecondary,
            title = { Text("Execution log · $viewingRunTitle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewingLogs.forEach { log ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(log.status, color = when (log.status) {
                                "SUCCESS" -> LocalOraclePalette.current.accent
                                "FAILED" -> LocalOraclePalette.current.error
                                else -> LocalOraclePalette.current.accent
                            }, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text(log.detail, color = LocalOraclePalette.current.text, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                    if (viewingLogs.isEmpty()) Text("No log entries were stored.", color = LocalOraclePalette.current.textSecondary, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewingRunId = null }) { Text("Close", color = LocalOraclePalette.current.accent) } }
        )
    }
}

@Composable
private fun FlowTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) LocalOraclePalette.current.surfaceRaised else Color.Transparent,
            contentColor = if (selected) LocalOraclePalette.current.accent else LocalOraclePalette.current.textSecondary
        ),
        contentPadding = PaddingValues(vertical = 7.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun FlowEmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OracleGlyphIcon(OracleGlyph.Settings, Modifier.size(30.dp), LocalOraclePalette.current.textSecondary)
        Text(title, color = LocalOraclePalette.current.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = LocalOraclePalette.current.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun WorkflowStepCard(
    index: Int,
    step: WorkflowStepEntity,
    onChange: (WorkflowStepEntity) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val action = WorkflowAction.from(step.actionType)
    Surface(color = LocalOraclePalette.current.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(index + 1).toString().padStart(2, '0')}", color = LocalOraclePalette.current.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { menuExpanded = true }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(action?.label ?: "Unsupported action", color = LocalOraclePalette.current.accent, fontSize = 10.sp)
                        OracleGlyphIcon(OracleGlyph.Settings, Modifier.size(13.dp), LocalOraclePalette.current.textSecondary)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        WorkflowAction.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Column {
                                    Text(candidate.label, color = LocalOraclePalette.current.text, fontSize = 11.sp)
                                    Text(candidate.help, color = LocalOraclePalette.current.textSecondary, fontSize = 9.sp)
                                } },
                                onClick = {
                                    menuExpanded = false
                                    onChange(step.copy(actionType = candidate.key, title = "", input = ""))
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    OracleGlyphIcon(OracleGlyph.ArrowUp, Modifier.size(15.dp), LocalOraclePalette.current.textSecondary)
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    OracleGlyphIcon(OracleGlyph.ChevronDown, Modifier.size(15.dp), LocalOraclePalette.current.textSecondary)
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    OracleGlyphIcon(OracleGlyph.Trash, Modifier.size(15.dp), LocalOraclePalette.current.textSecondary)
                }
            }
            FlowField(
                value = step.title,
                onValueChange = { onChange(step.copy(title = it.take(WorkflowRules.MAX_TITLE_CHARS))) },
                placeholder = if (step.actionType == WorkflowAction.SEARCH_LIBRARY.key) "Search query" else "Note title",
                singleLine = true
            )
            if (step.actionType != WorkflowAction.SEARCH_LIBRARY.key) {
                FlowField(
                    value = step.input,
                    onValueChange = { onChange(step.copy(input = it.take(WorkflowRules.MAX_INPUT_CHARS))) },
                    placeholder = if (step.actionType == WorkflowAction.CREATE_NOTE.key) "Text for the new note…" else "Text to append…",
                    singleLine = false,
                    minLines = 3
                )
            } else {
                Text("Searches the existing on-device document index. Results are recorded in the run log.", color = LocalOraclePalette.current.textSecondary, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun FlowField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    minLines: Int = 1
) {
    val palette = LocalOraclePalette.current
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.canvas)
            .border(1.dp, palette.outline, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 5,
        textStyle = TextStyle(
            color = palette.text,
            fontFamily = OracleSans,
            fontSize = 12.sp,
            lineHeight = 18.sp
        ),
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = palette.textTertiary,
                        fontFamily = OracleSans,
                        fontSize = 12.sp
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun RunStatus(status: String) {
    val color = when (status) {
        "SUCCEEDED" -> LocalOraclePalette.current.accent
        "FAILED" -> LocalOraclePalette.current.error
        "RUNNING" -> LocalOraclePalette.current.accent
        else -> LocalOraclePalette.current.textSecondary
    }
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(5.dp)) {
        Text(status, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}
