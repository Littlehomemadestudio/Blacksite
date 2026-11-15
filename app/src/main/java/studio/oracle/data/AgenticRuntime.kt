package studio.oracle.data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AgenticSettings(val thinkingLevel:String="high",val thinkingSummaries:String="auto",val maxOutputTokens:Int=8192,val temperature:Double=0.35,val topP:Double=0.95,val topK:Int=40,val googleSearch:Boolean=true,val hostedPython:Boolean=true,val urlContext:Boolean=true,val localTools:Boolean=true){
 fun validated()=copy(thinkingLevel=thinkingLevel.takeIf{it in setOf("minimal","low","medium","high","xhigh")}?:"high",thinkingSummaries=thinkingSummaries.takeIf{it in setOf("auto","none")}?:"auto",maxOutputTokens=maxOutputTokens.coerceIn(256,32768),temperature=temperature.coerceIn(0.0,2.0),topP=topP.coerceIn(0.0,1.0),topK=topK.coerceIn(1,100))
}
data class AgenticEvent(val kind:String,val title:String,val detail:String="",val code:String="")
sealed interface AgenticResult{data class Success(val text:String,val model:String,val interactionId:String?):AgenticResult;data class Failed(val message:String):AgenticResult}
interface AgenticToolExecutor{fun declarations():JSONArray;suspend fun execute(name:String,arguments:JSONObject):String}

/** Stateful Gemini Interactions API loop. Local function calls are executed by the app and returned by call_id. */
class GeminiAgenticRuntime(private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(150,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).build()){
 suspend fun run(apiKey:String,prompt:String,model:String,settings:AgenticSettings,executor:AgenticToolExecutor,onEvent:(AgenticEvent)->Unit={}):AgenticResult{
  if(apiKey.isBlank())return AgenticResult.Failed("Connect a Gemini API key in Settings.")
  if(prompt.isBlank())return AgenticResult.Failed("Write an instruction first.")
  if(!model.matches(Regex("[A-Za-z0-9._-]+")))return AgenticResult.Failed("Invalid Gemini model identifier.")
  val cfg=settings.validated()
  val tools=JSONArray().apply{if(cfg.googleSearch)put(JSONObject().put("type","google_search"));if(cfg.hostedPython)put(JSONObject().put("type","code_execution"));if(cfg.urlContext)put(JSONObject().put("type","url_context"));if(cfg.localTools){val a=executor.declarations();for(i in 0 until a.length())put(a.getJSONObject(i))}}
  var interactionId:String?=null;var input:Any=("You are ORACLE, an autonomous AI operator inside an Android workspace. Use available tools when they materially help; do not merely describe actions you can perform. For multi-step work, plan briefly, execute tools, inspect results, and continue until the task is complete. Never claim an action succeeded unless a tool result confirms it. Phone file tools are restricted to the folder the user granted. The app asks for approval before creating or changing files and before local Python execution. Keep progress updates concise and do not expose private chain-of-thought.\\n\\n"+prompt);val transcript=StringBuilder()
  repeat(12){round->
   val body=JSONObject().put("model",model).put("input",input).put("tools",tools).put("generation_config",JSONObject().put("thinking_level",cfg.thinkingLevel).put("thinking_summaries",cfg.thinkingSummaries).put("max_output_tokens",cfg.maxOutputTokens).put("temperature",cfg.temperature).put("top_p",cfg.topP).put("top_k",cfg.topK))
   interactionId?.let{body.put("previous_interaction_id",it)}
   val request=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/interactions").header("x-goog-api-key",apiKey).header("Api-Revision","2026-05-20").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
   val root=try{http.newCall(request).await().use{response->val raw=response.body?.string().orEmpty();if(!response.isSuccessful)return AgenticResult.Failed(apiError(response.code,raw));JSONObject(raw)}}catch(c:CancellationException){throw c}catch(e:IOException){return AgenticResult.Failed("Network error contacting Gemini Interactions API.")}catch(e:Exception){return AgenticResult.Failed(e.message?.take(240)?:"Could not read Gemini interaction.")}
   interactionId=root.optString("id").takeIf{it.isNotBlank()}?:interactionId
   root.optJSONObject("usage")?.let { usage ->
    val ledger = listOf("input" to "total_input_tokens", "output" to "total_output_tokens", "thinking" to "total_thought_tokens", "cached" to "total_cached_tokens", "tools" to "total_tool_use_tokens")
     .mapNotNull { (label,key) -> if (usage.has(key)) label + " " + usage.optInt(key) else null }.joinToString("  ·  ")
    if (ledger.isNotBlank()) onEvent(AgenticEvent("usage","Context ledger",ledger))
   }
   val steps=root.optJSONArray("steps")?:JSONArray();val results=JSONArray();var calls=0
   for(i in 0 until steps.length()){
    val s=steps.optJSONObject(i)?:continue
    when(s.optString("type")){
     "thought"->blocks(s.optJSONArray("summary")).takeIf{it.isNotBlank()}?.let{onEvent(AgenticEvent("thinking","Reasoning summary",it))}
     "google_search_call"->onEvent(AgenticEvent("search","Searching the web","Google Search is running."))
     "google_search_result"->onEvent(AgenticEvent("search_result","Web results received",s.toString().take(6000)))
     "code_execution_call"->{val code=s.optJSONObject("arguments")?.optString("code").orEmpty();onEvent(AgenticEvent("python","Running hosted Python","Gemini hosted Python environment",code))}
     "code_execution_result"->onEvent(AgenticEvent("python_result","Hosted Python result",s.opt("result")?.toString().orEmpty().take(12000)))
     "function_call"->{val name=s.optString("name");val id=s.optString("id");val args=s.optJSONObject("arguments")?:JSONObject();if(name=="run_local_python")onEvent(AgenticEvent("python_local","Running local Python","Bundled CPython runtime",args.optString("code")))else onEvent(AgenticEvent("tool",name,args.toString()));val result=try{executor.execute(name,args)}catch(c:CancellationException){throw c}catch(e:Exception){"Tool failed: "+(e.message?:"unknown error")};if(name=="run_local_python")onEvent(AgenticEvent("python_result","Local Python output",result.take(12000)))else onEvent(AgenticEvent("tool_result",name+" completed",result.take(12000)));results.put(JSONObject().put("type","function_result").put("name",name).put("call_id",id).put("result",JSONArray().put(JSONObject().put("type","text").put("text",result.take(24000)))));calls++}
     "model_output"->blocks(s.optJSONArray("content")).takeIf{it.isNotBlank()}?.let{if(transcript.isNotEmpty())transcript.append("\n");transcript.append(it)}
    }
   }
   if(calls==0){val finalText=root.optString("output_text");if(transcript.isEmpty()&&finalText.isNotBlank())transcript.append(finalText);return if(transcript.isNotBlank())AgenticResult.Success(transcript.toString(),model,interactionId)else AgenticResult.Failed("Gemini completed without a text response.")}
   if(round==11)return AgenticResult.Failed("Agent reached the 12-round tool-call safety limit.")
   if(interactionId.isNullOrBlank())return AgenticResult.Failed("Tool call had no interaction ID; execution stopped safely.")
   input=results
  }
  return AgenticResult.Failed("Agent stopped before producing a final answer.")
 }
 private fun blocks(a:JSONArray?)=buildString{if(a==null)return@buildString;for(i in 0 until a.length()){val b=a.optJSONObject(i)?:continue;if(b.optString("type")=="text"){val t=b.optString("text");if(t.isNotBlank()){if(isNotEmpty())append("\n");append(t)}}}}
 private fun apiError(code:Int,raw:String):String{val detail=runCatching{JSONObject(raw).optJSONObject("error")?.optString("message")}.getOrNull();return detail?.takeIf{it.isNotBlank()}?.take(260)?:when(code){401,403->"Gemini rejected the API key or API access is disabled.";404->"This model or Interactions API is unavailable for this key. Select a supported model.";429->"Gemini rate limit or quota reached.";else->"Gemini Interactions API failed (HTTP "+code+")."}}
}
private suspend fun Call.await():Response=suspendCancellableCoroutine{c->enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){c.resumeWith(Result.failure(e))};override fun onResponse(call:Call,response:Response){c.resume(response){_,r,_->r.close()}}});c.invokeOnCancellation{cancel()}}
