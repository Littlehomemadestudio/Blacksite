package studio.oracle

import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.oracle.data.AgentConversationPolicy
import studio.oracle.data.AgentRepository
import studio.oracle.data.AgentTurn
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiModel
import studio.oracle.data.GeminiModelsResult
import studio.oracle.data.ModelPreferences
import studio.oracle.data.local.AgentMessageEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.GeminiConnectionResult
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.LibraryScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink=Color(0xFF202124)
private val Panel=Color(0xFFF7F8FA)
private val Raised=Color(0xFFEFF1F5)
private val Line=Color(0xFFE5E7EB)
private val Paper=Color(0xFF202124)
private val Muted=Color(0xFF777D87)
private val Accent=Color(0xFF2563EB)
private val Violet=Color(0xFF8B5CF6)
private val Glass=Color(0xDFFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OracleApp() }
    }
}

@Composable
private fun OracleApp() {
 var prompt by remember { mutableStateOf("") }
 var settings by remember { mutableStateOf(false) }
 MaterialTheme(colorScheme=lightColorScheme(background=Color(0xFFF8F9FB),surface=Color.White,surfaceVariant=Panel,primary=Accent,secondary=Violet,onBackground=Paper,onSurface=Paper,onSurfaceVariant=Muted)) {
  Surface(Modifier.fillMaxSize(),color=Color(0xFFF8F9FB)) {
   if(settings) Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().height(54.dp),verticalAlignment=Alignment.CenterVertically) {
     IconButton(onClick={settings=false}) { Icon(Icons.Default.ArrowBack,"Back") }
     Text("Settings",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
    }
    SettingsScreen()
   } else AgentScreen(prompt,{prompt=it},onSettings={settings=true})
  }
 }
}

@Composable
private fun AgentScreen(prompt: String, onPrompt: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val repository = remember(context) {
        AgentRepository(OracleDatabase.get(context).agentDao())
    }
    val modelPreferences = remember(context) { ModelPreferences(context) }
    val scope = rememberCoroutineScope()

    val sessions by repository.sessions.collectAsState(initial = emptyList())
    val model by modelPreferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(emptyList<AgentMessageEntity>()) }
    var availableModels by remember {
        mutableStateOf(listOf(GeminiModel(ModelPreferences.DEFAULT_MODEL, "Gemini 2.5 Flash")))
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var syncingModels by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("READY / LOCAL SESSION") }
    var error by remember { mutableStateOf<String?>(null) }
    var failedPrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        activeSessionId = repository.ensureSession(model)
    }
    LaunchedEffect(sessions) {
        val current = activeSessionId
        if (current == null && sessions.isNotEmpty()) {
            activeSessionId = sessions.first().id
        } else if (current != null && sessions.none { it.id == current }) {
            activeSessionId = sessions.firstOrNull()?.id
        }
    }
    LaunchedEffect(activeSessionId) {
        val id = activeSessionId
        if (id == null) {
            messages = emptyList()
        } else {
            repository.messages(id).collect { messages = it }
        }
    }

    fun refreshModels() {
        if (syncingModels) return
        scope.launch {
            syncingModels = true
            error = null
            status = "LOADING AVAILABLE GEMINI MODELS"
            try {
                val key = keyStore.read().orEmpty()
                when (val result = api.listModels(key)) {
                    is GeminiModelsResult.Success -> {
                        availableModels = result.models
                        status = if (result.models.isEmpty()) {
                            "NO GENERATECONTENT MODELS AVAILABLE"
                        } else {
                            "LOADED " + result.models.size + " MODELS"
                        }
                        if (result.models.isEmpty()) {
                            error = "This key exposes no models that support generateContent."
                        }
                    }
                    is GeminiModelsResult.Failed -> {
                        error = result.message
                        status = "MODEL DISCOVERY FAILED"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not load models. Check the stored key and network connection."
                status = "MODEL DISCOVERY FAILED"
            } finally {
                syncingModels = false
            }
        }
    }

    val menuModels = remember(availableModels, model) {
        (availableModels + GeminiModel(model, model)).distinctBy { it.name }
    }

    var toolsExpanded by remember { mutableStateOf(false) }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val uiScope=rememberCoroutineScope()
    val glass=Brush.verticalGradient(listOf(Color.White.copy(.99f),Color(0xFFF1F4FA).copy(.97f)))
    ModalNavigationDrawer(drawerState=drawer,scrimColor=Color(0x330F172A),drawerContent={
      ModalDrawerSheet(Modifier.fillMaxHeight().width(305.dp),drawerContainerColor=Color(0xFFF8F9FC),drawerShape=RoundedCornerShape(topEnd=25.dp,bottomEnd=25.dp)) {
       Column(Modifier.fillMaxSize().padding(horizontal=15.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
         Box(Modifier.size(38.dp).background(Brush.linearGradient(listOf(Color(0xFF3B82F6),Color(0xFF8B5CF6))),RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) { Icon(Icons.Default.AutoAwesome,null,tint=Color.White) }
         Spacer(Modifier.width(10.dp)); Text("ORACLE",fontSize=18.sp,fontWeight=FontWeight.Bold)
         Spacer(Modifier.weight(1f)); IconButton(onClick={uiScope.launch{drawer.close()}}){Icon(Icons.Default.Close,"Close")}
        }
        Spacer(Modifier.height(18.dp))
        Surface(onClick={uiScope.launch{activeSessionId=repository.createSession(model);error=null;failedPrompt=null;drawer.close()}},color=Color.White,shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,Line),shadowElevation=2.dp) {
         Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Add,null,tint=Accent);Spacer(Modifier.width(10.dp));Text("New chat",fontWeight=FontWeight.SemiBold)}
        }
        Spacer(Modifier.height(22.dp));Text("RECENT",fontSize=10.sp,color=Muted,fontWeight=FontWeight.SemiBold,letterSpacing=1.2.sp);Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
         items(sessions,key={it.id}) { session ->
          val selected=session.id==activeSessionId
          Row(Modifier.fillMaxWidth().background(if(selected)Color(0xFFEAF0FF)else Color.Transparent,RoundedCornerShape(11.dp)).clickable{activeSessionId=session.id;error=null;failedPrompt=null;uiScope.launch{drawer.close()}}.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
           Icon(Icons.Default.ChatBubbleOutline,null,tint=if(selected)Accent else Muted,modifier=Modifier.size(17.dp));Spacer(Modifier.width(10.dp))
           Text(session.title.ifBlank{"New chat"},fontSize=13.sp,color=if(selected)Color(0xFF1D4ED8)else Paper,maxLines=1,overflow=TextOverflow.Ellipsis)
          }
         }
        }
        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth().clickable{onSettings();uiScope.launch{drawer.close()}}.padding(vertical=15.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Settings,null,tint=Muted);Spacer(Modifier.width(12.dp));Text("Settings",fontSize=13.sp) }
       }
      }
    }) {
     Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FB))) {
      Row(Modifier.fillMaxWidth().height(58.dp).background(Color.White.copy(.94f)).padding(horizontal=9.dp),verticalAlignment=Alignment.CenterVertically) {
       IconButton(onClick={uiScope.launch{drawer.open()}}){Icon(Icons.Default.Menu,"Chat history",tint=Paper)}
       Column(Modifier.weight(1f)){Text("ORACLE",fontSize=15.sp,fontWeight=FontWeight.Bold,letterSpacing=.8.sp);Text("Gemini",fontSize=10.sp,color=Muted)}
       IconButton(onClick={uiScope.launch{activeSessionId=repository.createSession(model);error=null;failedPrompt=null}},enabled=!busy){Icon(Icons.Default.Edit,"New chat",tint=Paper)}
      }
      HorizontalDivider(color=Color(0xFFEDEFF3))
      Box(Modifier.weight(1f).fillMaxWidth()) {
       if(messages.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).background(Brush.linearGradient(listOf(Color(0xFFDBEAFE),Color(0xFFEDE9FE))),RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.AutoAwesome,null,tint=Color(0xFF4F6FE8),modifier=Modifier.size(28.dp))}
        Spacer(Modifier.height(22.dp));Text("What can I help with?",fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF25272B))
       } else LazyColumn(Modifier.fillMaxSize().padding(horizontal=17.dp),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(top=20.dp,bottom=24.dp)) {
        items(messages,key={it.id}) { m ->
         if(m.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
          Surface(color=Color(0xFFF0F2F5),shape=RoundedCornerShape(22.dp),modifier=Modifier.widthIn(max=310.dp)){Text(m.content,Modifier.padding(horizontal=16.dp,vertical=12.dp),fontSize=15.sp,lineHeight=23.sp,color=Paper)}
         } else Text(m.content,Modifier.fillMaxWidth(),fontSize=15.sp,lineHeight=24.sp,color=Color(0xFF25272B))
        }
        if(busy)item{Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(17.dp),color=Accent,strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text("Thinking",fontSize=13.sp,color=Muted)}}
       }
      }
      if(error!=null) Text(error!!,Modifier.padding(horizontal=18.dp,vertical=4.dp),color=Color(0xFFB42318),fontSize=12.sp)
      Surface(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=10.dp).animateContentSize(),shape=RoundedCornerShape(27.dp),color=Glass,border=BorderStroke(1.dp,Color(0xFFE3E7EF)),shadowElevation=8.dp) {
       Column(Modifier.background(glass).padding(horizontal=12.dp,vertical=8.dp)) {
        OutlinedTextField(value=prompt,onValueChange=onPrompt,modifier=Modifier.fillMaxWidth(),placeholder={Text("Message ORACLE",color=Color(0xFF9298A3),fontSize=16.sp)},minLines=1,maxLines=6,shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Color.Transparent,unfocusedBorderColor=Color.Transparent,focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedTextColor=Paper,unfocusedTextColor=Paper,cursorColor=Accent))
        Row(verticalAlignment=Alignment.CenterVertically) {
         IconButton(onClick={toolsExpanded=!toolsExpanded},modifier=Modifier.size(40.dp)){Icon(Icons.Default.Add,"Expand tools",tint=Color(0xFF626A76))}
         TextButton(onClick={toolsExpanded=!toolsExpanded},contentPadding=PaddingValues(horizontal=6.dp)){Text("Tools",fontSize=12.sp,color=Color(0xFF626A76));Icon(if(toolsExpanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,tint=Muted,modifier=Modifier.size(16.dp))}
         Spacer(Modifier.weight(1f))
         if(busy) IconButton(onClick={requestJob?.cancel()}){Icon(Icons.Default.StopCircle,"Stop",tint=Color(0xFFDC5555))}
         else IconButton(onClick={
          val submitted=prompt.trim();if(submitted.isBlank())return@IconButton
          val selected=activeSessionId;val turns=messages.map{AgentTurn(it.role,it.content)}
          val retry=failedPrompt==submitted&&turns.lastOrNull()?.let{it.role=="user"&&it.content==submitted}==true
          val history=if(retry)turns.dropLast(1)else turns
          onPrompt("");error=null;busy=true
          requestJob=scope.launch {
           try {
            val sid=selected?:repository.createSession(model).also{activeSessionId=it};val key=keyStore.read().orEmpty()
            if(key.isBlank()){error="Add your Gemini API key in Settings to start chatting.";failedPrompt=submitted;onPrompt(submitted);return@launch}
            if(!retry)repository.appendMessage(sid,"user",submitted,model)
            when(val result=api.generate(key,submitted,AgentConversationPolicy.context(history),model)){
             is studio.oracle.data.GeminiReplyResult.Success->{repository.appendMessage(sid,"assistant",result.text,result.model);failedPrompt=null}
             is studio.oracle.data.GeminiReplyResult.Failed->{error=result.message;failedPrompt=submitted}
            }
           }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){error="Something went wrong. Check your connection and try again.";failedPrompt=submitted}
           finally{busy=false;requestJob=null}
          }
         },enabled=prompt.isNotBlank()){Icon(Icons.Default.ArrowUpward,"Send",tint=Color.White,modifier=Modifier.background(if(prompt.isBlank())Color(0xFFB8C0CC)else Accent,CircleShape).padding(9.dp))}
        }
        AnimatedVisibility(visible=toolsExpanded,enter=fadeIn()+slideInVertically{-it/3},exit=fadeOut()+slideOutVertically{-it/3}) {
         Column(Modifier.fillMaxWidth().padding(top=7.dp,bottom=5.dp)) {
          HorizontalDivider(color=Color(0xFFE8EBF0))
          Row(verticalAlignment=Alignment.CenterVertically) {
           Icon(Icons.Default.AutoAwesome,null,tint=Accent,modifier=Modifier.size(17.dp));Spacer(Modifier.width(8.dp));Text("Model",fontSize=13.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.weight(1f))
           Box {
            TextButton(onClick={modelMenuExpanded=true}){Text(menuModels.firstOrNull{it.name==model}?.displayName?:model,color=Accent,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.Default.ExpandMore,null,tint=Accent)}
            DropdownMenu(expanded=modelMenuExpanded,onDismissRequest={modelMenuExpanded=false}){menuModels.forEach{candidate->DropdownMenuItem(text={Text(candidate.displayName)},onClick={modelMenuExpanded=false;scope.launch{modelPreferences.setSelectedModel(candidate.name);activeSessionId?.let{repository.updateModel(it,candidate.name)}}})}}
           }
           IconButton(onClick={refreshModels},enabled=!syncingModels){Icon(Icons.Default.Refresh,"Refresh models",tint=Muted)}
          }
          Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Search","Reason","Attach").forEach{label->Surface(color=Color(0xFFF3F5F9),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Color(0xFFE7EAF0))){Text(label,Modifier.padding(horizontal=12.dp,vertical=8.dp),fontSize=11.sp,color=Color(0xFF687080))}}}
         }
        }
       }
      }
     }
    }
   }
  }
}
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf(store.hasKey()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (stored) "Encrypted Gemini credential found on this device." else "No Gemini key connected.") }
    var success by remember { mutableStateOf(stored) }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("MODEL CONNECTION", color = Accent, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Text("Bring your own intelligence.", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Connect Gemini directly. Your key is encrypted with Android Keystore and stays on this device.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        Surface(color = Panel, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("GEMINI API KEY", color = Paper, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (stored) Text("ENCRYPTED", color = Accent, fontSize = 8.sp, letterSpacing = 1.sp)
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(if (stored) "Key saved · enter a new key to replace" else "Paste your Gemini API key", color = Muted, fontSize = 11.sp) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Verifying key with Google Gemini…"
                            val candidate = keyInput.trim().ifBlank { runCatching { store.read().orEmpty() }.getOrDefault("") }
                            when (val result = api.validateKey(candidate)) {
                                is GeminiConnectionResult.Connected -> {
                                    if (keyInput.isNotBlank()) {
                                        runCatching { store.save(candidate) }
                                            .onSuccess { stored = true; keyInput = "" }
                                            .onFailure { status = "Verified, but secure storage failed. Try again."; success = false }
                                    }
                                    if (keyInput.isBlank() || stored) { status = result.message; success = true }
                                }
                                is GeminiConnectionResult.Failed -> { status = result.message; success = false }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && (keyInput.isNotBlank() || stored),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink, disabledContainerColor = Raised)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink)
                    else Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "VERIFYING…" else "VERIFY & SECURE KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Info, null,
                        tint = if (success) Accent else Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = if (success) Accent else Muted, fontSize = 10.sp)
                }
                if (stored) {
                    TextButton(onClick = {
                        store.clear()
                        stored = false
                        success = false
                        status = "Stored key removed from this device."
                    }) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("REMOVE STORED KEY", fontSize = 9.sp)
                    }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PRIVACY BY DESIGN", color = Accent, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text("• API key encrypted using AES-GCM; encryption key held by Android Keystore.", color = Muted, fontSize = 10.sp)
                Text("• Key is sent only to Google's Gemini API over HTTPS when you invoke a model action.", color = Muted, fontSize = 10.sp)
                Text("• No key is embedded in the APK, source code, analytics, or logs.", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("ORACLE / MODEL GATEWAY", color = Muted, fontSize = 8.sp, letterSpacing = 1.3.sp)
    }
}

