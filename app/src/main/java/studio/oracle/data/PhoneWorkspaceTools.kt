package studio.oracle.data
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** SAF-scoped tools: all paths are relative to a folder explicitly granted by the user. */
class PhoneWorkspaceTools(private val context:Context,private val grantedTree:Uri?,private val confirm: suspend (String) -> Boolean,private val python: suspend (String) -> String):AgenticToolExecutor{
 override fun declarations()=JSONArray().apply{
  put(fn("list_phone_files","List entries in the user-granted folder.",schema("path" to prop("Relative folder path; empty means root.")),arrayOf("path")))
  put(fn("read_phone_file","Read a UTF-8 text file inside the granted folder.",schema("path" to prop("Relative file path.")),arrayOf("path")))
  put(fn("create_phone_file","Create a new UTF-8 text file inside the granted folder.",schema("path" to prop("Relative path and filename."),"content" to prop("File contents.")),arrayOf("path","content")))
  put(fn("append_phone_file","Append text to an existing UTF-8 file.",schema("path" to prop("Relative file path."),"content" to prop("Text to append.")),arrayOf("path","content")))
  put(fn("run_local_python","Execute Python 3 in ORACLE's bundled local runtime.",schema("code" to prop("Python source code.")),arrayOf("code")))
 }
 override suspend fun execute(name:String,args:JSONObject):String=withContext(Dispatchers.IO){
  when(name){
   "list_phone_files"->{val root=root()?:return@withContext "No folder permission. Ask the user to grant a folder in ORACLE.";val folder=resolve(root,args.optString("path"))?:return@withContext "Folder not found.";folder.listFiles().take(250).joinToString("\n"){(if(it.isDirectory)"DIR  " else "FILE ")+it.name.orEmpty()}.ifBlank{"Folder is empty."}}
   "read_phone_file"->{val root=root()?:return@withContext "No folder permission.";val file=resolve(root,args.optString("path"))?:return@withContext "File not found.";if(!file.isFile)return@withContext "Path is not a file.";if(file.length()>524288)return@withContext "File exceeds 512 KB.";context.contentResolver.openInputStream(file.uri)?.use{BufferedReader(InputStreamReader(it,StandardCharsets.UTF_8)).readText().take(120000)}?:"Could not open file."}
   "create_phone_file"->{val parts=safe(args.optString("path"))?:return@withContext "Unsafe path rejected.";val content=args.optString("content");if(parts.isEmpty()||content.length>256000)return@withContext "Invalid path or content exceeds 256 KB.";if(!confirm("Create "+parts.last()+" in the folder you granted to ORACLE?"))return@withContext "User declined file creation.";val root=root()?:return@withContext "No folder permission.";val parent=ensureParent(root,parts.dropLast(1))?:return@withContext "Could not create parent folder.";if(parent.findFile(parts.last())!=null)return@withContext "File already exists; choose another name.";val file=parent.createFile("text/plain",parts.last())?:return@withContext "Android could not create the file.";context.contentResolver.openOutputStream(file.uri,"wt")?.use{it.write(content.toByteArray(StandardCharsets.UTF_8))}?:return@withContext "Could not write file.";"Created "+parts.last()}
   "append_phone_file"->{val parts=safe(args.optString("path"))?:return@withContext "Unsafe path rejected.";val content=args.optString("content");if(parts.isEmpty()||content.length>256000)return@withContext "Invalid path or append exceeds 256 KB.";if(!confirm("Append "+content.length+" characters to "+parts.joinToString("/")+"?"))return@withContext "User declined file modification.";val root=root()?:return@withContext "No folder permission.";val file=resolve(root,parts.joinToString("/"))?:return@withContext "File not found.";if(!file.isFile)return@withContext "Path is not a file.";context.contentResolver.openOutputStream(file.uri,"wa")?.use{it.write(content.toByteArray(StandardCharsets.UTF_8))}?:return@withContext "Could not append.";"Appended "+content.length+" characters to "+parts.last()}
   "run_local_python"->{val code=args.optString("code");if(code.isBlank()||code.length>48000)return@withContext "Python source empty or exceeds 48 KB.";if(!confirm("Execute "+code.lines().size+" lines of Python locally on this device?"))return@withContext "User declined Python execution.";python(code)}
   else->"Unknown or disabled tool: "+name
  }
 }
 private fun root():DocumentFile?{val tree=grantedTree?:return null;if(context.contentResolver.persistedUriPermissions.none{it.uri==tree&&it.isReadPermission})return null;return DocumentFile.fromTreeUri(context,tree)}
 private fun safe(raw:String):List<String>?{if(raw.length>512||raw.startsWith("/")||raw.contains("\\"))return null;val p=raw.split("/").filter{it.isNotBlank()};if(p.any{it=="."||it==".."||it.length>120||it.any(Char::isISOControl)})return null;return p}
 private fun resolve(root:DocumentFile,raw:String):DocumentFile?{if(raw.isBlank())return root;val p=safe(raw)?:return null;var current=root;for(part in p)current=current.findFile(part)?:return null;return current}
 private fun ensureParent(root:DocumentFile,parts:List<String>):DocumentFile?{var current=root;for(part in parts){current=current.findFile(part)?:current.createDirectory(part)?:return null;if(!current.isDirectory)return null};return current}
 private fun prop(desc:String)=JSONObject().put("type","string").put("description",desc)
 private fun schema(vararg p:Pair<String,JSONObject>)=JSONObject().put("type","object").put("properties",JSONObject().apply{p.forEach{put(it.first,it.second)}})
 private fun fn(name:String,desc:String,parameters:JSONObject,required:Array<String>)=JSONObject().put("type","function").put("name",name).put("description",desc).put("parameters",parameters.put("required",JSONArray(required.toList())))
}
