package studio.oracle.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full embedded CPython environment. Code runs inside the Android app process,
 * with normal Python builtins, standard-library imports, network access and an
 * app-private working directory. This is not a separate operating-system shell.
 */
class LocalPythonRuntime(private val context: Context) {
    private val workspace: File by lazy {
        File(context.filesDir, "oracle-python").apply { mkdirs() }
    }

    suspend fun execute(source: String): String = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "Python source cannot be empty." }
        require(source.length <= 16 * 1024) { "Python source exceeds 16 KB." }
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance()
            .getModule("oracle_runner")
            .callAttr("run", source, workspace.absolutePath)
            .toString()
    }

    fun workspaceDirectory(): File = workspace
}
