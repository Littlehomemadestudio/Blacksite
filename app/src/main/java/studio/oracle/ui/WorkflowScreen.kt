package studio.oracle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import studio.oracle.data.WorkflowRunOrigin
import studio.oracle.data.WorkflowSchedulePolicy
import studio.oracle.data.WorkflowScheduleResult
import studio.oracle.data.WorkflowTriggerScheduler
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunLogEntity
import studio.oracle.data.local.WorkflowStepEntity
import java.util.UUID

@Composable
fun WorkflowScreen(workspaceId: String) {
    val context = LocalContext.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database, workspaceId) { WorkflowRepository(database.workflowDao(), workspaceId) }
    val scheduleManager = remember(context, database, workspaceId) {
        WorkflowTriggerScheduler(context, database.workflowTriggerDao(), database.workflowDao(), workspaceId)
    }
    val triggers by scheduleManager.triggers.collectAsState(initial = emptyList())
    val documentRepository = remember(context, database) {
        DocumentRepository(context, database.documentDao(), workspaceId)
    }
    val runner = remember(database, documentRepository, workspaceId) {
        LocalWorkflowRunner(database.workflowDao(), database.audioMemoryDao(), documentRepository, workspaceId)
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
    var status by remember { mutableStateOf("LOCAL WORKSPACE · MANUAL + OPTIONAL SCHEDULED TRIGGERS") }
    var saving by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var showRunConfirmation by remember { mutableStateOf(false) }
    var showScheduleConfirmation by remember { mutableStateOf(false) }
    var scheduleInterval by remember { mutableStateOf(60L) }
    var scheduleMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var viewingRunId by remember { mutableStateOf<String?>(null) }
    var viewingLogs by remember { mutableStateOf(emptyList<WorkflowRunLogEntity>()) }
    var viewingRunTitle by remember { mutableStateOf("") }

    val selectedWorkflow = workflows.firstOrNull { it.id == selectedWorkflowId }
    val selectedTrigger = triggers.firstOrNull { it.workflowId == selectedWorkflowId && it.enabled }

    LaunchedEffect(selectedWorkflowId, selectedTrigger?.intervalMinutes) {
        scheduleInterval = selectedTrigger?.intervalMinutes ?: 60L
    }

    LaunchedEffect(workspaceId) {
        selectedWorkflowId = null
        loadedWorkflowId = null
        draftTitle = ""
        draftDescription = ""
        steps = emptyList()
        tab = "WORKFLOWS"
        viewingRunId = null
        viewingLogs = emptyList()
        status = "WORKSPACE CHANGED · LOCAL DATA FILTERED"
    }

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
        viewingLogs = if (id == null) emptyList() else database.workflowDao().logsForRun(id, workspaceId)
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
        Modifier.fillMaxSize().background(LocalOraclePalette.current.canvas).imePadding().animateContentSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AUTOMATION STUDIO", color = LocalOraclePalette.current.accent, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Text("Workflows", color = LocalOraclePalette.current.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Local actions · manual or optional scheduled runs", color = LocalOraclePalette.current.textSecondary, fontSize = 10.sp)
            }
            if (tab == "WORKFLOWS" && selectedWorkflowId == null) {
                OracleSolidAction(
                    label = "NEW WORKFLOW",
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
                    }
                )
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
                            Surface(color = LocalOraclePalette.current.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft), modifier = Modifier.animateContentSize()) {
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
                        FlowEmptyState("Build your first workflow", "Combine local notes, document searches and audio-memory retrieval. Nothing runs until you review and confirm it.")
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
                                            val scheduled = triggers.firstOrNull { it.workflowId == workflow.id && it.enabled }
                                            Text(
                                                scheduled?.let { "Scheduled · every ${WorkflowSchedulePolicy.intervalLabel(it.intervalMinutes)}" }
                                                    ?: workflow.description.ifBlank { "Manual workflow · local only" },
                                                color = LocalOraclePalette.current.textSecondary,
                                                fontSize = 10.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
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
                                        Text("MANUAL RUN  ·  ON-DEVICE EXECUTION  ·  NO NETWORK", color = LocalOraclePalette.current.textSecondary, fontSize = 8.sp, letterSpacing = .35.sp)
                                    }
                                }
                                Surface(
                                    color = LocalOraclePalette.current.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft),
                                    modifier = Modifier.fillMaxWidth().animateContentSize()
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    if (selectedTrigger?.enabled == true) "SCHEDULED TRIGGER" else "OPTIONAL RECURRING TRIGGER",
                                                    color = LocalOraclePalette.current.accent,
                                                    fontSize = 8.sp,
                                                    letterSpacing = 1.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    if (selectedTrigger?.enabled == true)
                                                        "Repeats every ${WorkflowSchedulePolicy.intervalLabel(selectedTrigger.intervalMinutes)}"
                                                    else "Run this saved workflow automatically",
                                                    color = LocalOraclePalette.current.text,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            if (selectedTrigger?.enabled == true) RunStatus("SCHEDULED")
                                            else Text("OFF", color = LocalOraclePalette.current.textTertiary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box {
                                                OracleTextAction(
                                                    label = "EVERY ${WorkflowSchedulePolicy.intervalLabel(scheduleInterval).uppercase()} ▾",
                                                    onClick = { scheduleMenuExpanded = true },
                                                    accent = true
                                                )
                                                DropdownMenu(
                                                    expanded = scheduleMenuExpanded,
                                                    onDismissRequest = { scheduleMenuExpanded = false }
                                                ) {
                                                    WorkflowSchedulePolicy.INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    WorkflowSchedulePolicy.intervalLabel(minutes),
                                                                    color = LocalOraclePalette.current.text,
                                                                    fontSize = 12.sp
                                                                )
                                                            },
                                                            onClick = {
                                                                scheduleInterval = minutes
                                                                scheduleMenuExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.weight(1f))
                                            if (selectedTrigger?.enabled == true) {
                                                OracleTextAction(
                                                    label = "UPDATE",
                                                    onClick = { showScheduleConfirmation = true },
                                                    accent = true
                                                )
                                                OracleTextAction(
                                                    label = "DISABLE",
                                                    onClick = {
                                                        val id = selectedWorkflowId ?: return@OracleTextAction
                                                        scope.launch {
                                                            try {
                                                                scheduleManager.disable(id)
                                                                status = "SCHEDULE DISABLED · WORKFLOW SAVED LOCALLY"
                                                            } catch (_: Exception) {
                                                                status = "COULD NOT DISABLE SCHEDULE"
                                                            }
                                                        }
                                                    },
                                                    accent = false
                                                )
                                            } else {
                                                OracleSolidAction(
                                                    label = "ENABLE SCHEDULE",
                                                    onClick = { showScheduleConfirmation = true },
                                                    enabled = !saving && !running && selectedWorkflow != null
                                                )
                                            }
                                        }
                                        Text(
                                            "Android may defer periodic work; this is not an exact-time alarm. Repeated Create/Append steps can add duplicate notes. Runs stay on this device.",
                                            color = LocalOraclePalette.current.textSecondary,
                                            fontSize = 9.sp,
                                            lineHeight = 13.sp
                                        )
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
                            OracleTextAction(
                                label = "ADD STEP",
                                onClick = {
                                    val id = selectedWorkflowId ?: return@OracleTextAction
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
                                accent = true
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OracleTextAction(
                            label = "DELETE",
                            onClick = { showDeleteConfirmation = true },
                            accent = false
                        )
                        Spacer(Modifier.weight(1f))
                        OracleTextAction(
                            label = if (saving) "SAVING…" else "SAVE",
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
                            accent = true
                        )
                        OracleSolidAction(
                            label = if (running) "RUNNING…" else "RUN",
                            onClick = {
                                val issue = WorkflowRules.validate(steps)
                                if (issue != null) status = issue else showRunConfirmation = true
                            },
                            enabled = !saving && !running && selectedWorkflow != null
                        )
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
                                steps,
                                origin = WorkflowRunOrigin.MANUAL
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


    if (showScheduleConfirmation) {
        val scheduleIsActive = selectedTrigger?.enabled == true
        AlertDialog(
            onDismissRequest = { showScheduleConfirmation = false },
            containerColor = LocalOraclePalette.current.surface,
            titleContentColor = LocalOraclePalette.current.text,
            textContentColor = LocalOraclePalette.current.textSecondary,
            title = { Text(if (scheduleIsActive) "Update recurring schedule?" else "Enable recurring schedule?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ORACLE will save this workflow and run it approximately every ${WorkflowSchedulePolicy.intervalLabel(scheduleInterval)}.")
                    Text("This is a local opt-in. WorkManager runs without Gemini or network access, but Android may defer execution to protect battery.")
                    Text("Create-note and append-note steps repeat too, so they may create duplicate notes or repeated text. Review the workflow before enabling it.", color = LocalOraclePalette.current.accent, fontSize = 10.sp)
                    Text("You can disable the schedule at any time. No exact-time alarm or event trigger is created.", fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScheduleConfirmation = false
                    val workflow = selectedWorkflow ?: return@TextButton
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
                            when (val result = scheduleManager.enable(workflow.id, scheduleInterval)) {
                                is WorkflowScheduleResult.Success -> status = result.message
                                is WorkflowScheduleResult.Failure -> status = result.message
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "SCHEDULE UPDATE FAILED · CHECK THE WORKFLOW AND LOCAL STORAGE"
                        } finally {
                            saving = false
                        }
                    }
                }) {
                    Text(if (scheduleIsActive) "Update schedule" else "Enable schedule", color = LocalOraclePalette.current.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScheduleConfirmation = false }) {
                    Text("Cancel", color = LocalOraclePalette.current.textSecondary)
                }
            }
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
                        scheduleManager.disable(id)
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
    val palette = LocalOraclePalette.current
    val target = if (selected) palette.surfaceRaised else Color.Transparent
    val background by animateColorAsState(target, tween(180), label = "workflow-tab")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            color = if (selected) palette.accent else palette.textSecondary,
            fontFamily = OracleMono,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = .45.sp
        )
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
    Surface(color = LocalOraclePalette.current.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LocalOraclePalette.current.outlineSoft), modifier = Modifier.animateContentSize()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(index + 1).toString().padStart(2, '0')}", color = LocalOraclePalette.current.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Box {
                    OracleTextAction(
                        label = action?.label?.uppercase() ?: "UNSUPPORTED ACTION",
                        onClick = { menuExpanded = true },
                        accent = true
                    )
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
                OracleIconButton(OracleGlyph.ArrowUp, "Move step up", onMoveUp, tint = LocalOraclePalette.current.textSecondary, size = 28.dp)
                OracleIconButton(OracleGlyph.ChevronDown, "Move step down", onMoveDown, tint = LocalOraclePalette.current.textSecondary, size = 28.dp)
                OracleIconButton(OracleGlyph.Trash, "Remove step", onRemove, tint = LocalOraclePalette.current.error, size = 28.dp)
            }
            FlowField(
                value = step.title,
                onValueChange = { onChange(step.copy(title = it.take(WorkflowRules.MAX_TITLE_CHARS))) },
                placeholder = if (step.actionType in setOf(WorkflowAction.SEARCH_LIBRARY.key, WorkflowAction.SEARCH_AUDIO.key)) "Search query" else "Note title",
                singleLine = true
            )
            if (step.actionType in setOf(WorkflowAction.CREATE_NOTE.key, WorkflowAction.APPEND_NOTE.key)) {
                FlowField(
                    value = step.input,
                    onValueChange = { onChange(step.copy(input = it.take(WorkflowRules.MAX_INPUT_CHARS))) },
                    placeholder = if (step.actionType == WorkflowAction.CREATE_NOTE.key) "Text for the new note…" else "Text to append…",
                    singleLine = false,
                    minLines = 3
                )
            } else {
                Text(
                    if (step.actionType == WorkflowAction.SEARCH_AUDIO.key)
                        "Searches local recording titles, transcripts, summaries, tasks and decisions. Audio stays on this device."
                    else
                        "Searches the existing on-device document index. Results are recorded in the run log.",
                    color = LocalOraclePalette.current.textSecondary,
                    fontSize = 9.sp
                )
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
