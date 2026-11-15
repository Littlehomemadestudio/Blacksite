package studio.oracle.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/**
 * Embedded on-device Python 3 runtime. The exposed runner applies an import and
 * AST allowlist, caps input/output, and enforces a cooperative execution timeout.
 */
object EmbeddedPythonExecutor {
    fun execute(context: Context, source: String): JSONObject {
        require(source.isNotBlank()) { "Write Python code before executing it." }
        require(source.length <= MAX_SOURCE_CHARS) { "Python source exceeds the 30 KB limit." }

        val appContext = context.applicationContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        val raw = Python.getInstance()
            .getModule("oracle_runtime")
            .callAttr("run", source)
            .toString()
        return runCatching { JSONObject(raw) }
            .getOrElse { JSONObject().put("ok", false).put("error", raw.take(MAX_OUTPUT_CHARS)) }
    }

    private const val MAX_SOURCE_CHARS = 30_000
    private const val MAX_OUTPUT_CHARS = 24_000
}
