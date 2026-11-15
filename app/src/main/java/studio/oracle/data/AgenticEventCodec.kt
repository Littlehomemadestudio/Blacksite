package studio.oracle.data

import org.json.JSONArray
import org.json.JSONObject

data class AgentAttachmentRef(
    val uri: String,
    val name: String,
    val mimeType: String
)

object AgenticEventCodec {
    fun encodeTrace(events: List<AgenticEvent>): String = JSONArray().apply {
        events.forEach { event ->
            put(JSONObject()
                .put("kind", event.kind)
                .put("title", event.title)
                .put("detail", event.detail)
                .put("code", event.code)
                .put("payload", event.payload))
        }
    }.toString()

    fun decodeTrace(raw: String): List<AgenticEvent> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgenticEvent(
                        kind = item.optString("kind"),
                        title = item.optString("title"),
                        detail = item.optString("detail"),
                        code = item.optString("code"),
                        payload = item.optString("payload")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun encodeAttachments(attachments: List<AgentAttachmentRef>): String = JSONArray().apply {
        attachments.forEach { attachment ->
            put(JSONObject()
                .put("uri", attachment.uri)
                .put("name", attachment.name)
                .put("mimeType", attachment.mimeType))
        }
    }.toString()

    fun decodeAttachments(raw: String): List<AgentAttachmentRef> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                if (uri.isNotBlank()) add(
                    AgentAttachmentRef(
                        uri = uri,
                        name = item.optString("name").ifBlank { "Attachment" },
                        mimeType = item.optString("mimeType").ifBlank { "application/octet-stream" }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
