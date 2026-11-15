package studio.oracle.data
import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Embedded CPython runner; invoked only after the app's per-run approval callback. */
class LocalPythonRuntime(private val context:Context){
 suspend fun execute(source:String):String=withContext(Dispatchers.IO){
  require(source.isNotBlank()){"Python source cannot be empty."}
  require(source.length<=48000){"Python source exceeds 48 KB."}
  if(!Python.isStarted())Python.start(AndroidPlatform(context.applicationContext))
  Python.getInstance().getModule("oracle_runner").callAttr("run",source).toString().take(16000)
 }
}
