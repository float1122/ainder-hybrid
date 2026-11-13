package app.ainderhybrid.hybrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Prompt templates from /shared/prompts (bundled as assets). `{{key}}` placeholders. */
class Prompts(private val load: (String) -> String) {
    fun render(task: AiTask, values: Map<String, String>): String = fill(load("prompts/${task.prompt}.txt"), values)

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{(\\w+)}}")

        /** Unknown placeholders become empty strings so a template never leaks its markers. */
        fun fill(template: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]].orEmpty() }
    }
}

/** Extracts the JSON object a model returned, tolerating ```json fences and chatter around it. */
object JsonExtract {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun obj(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }
}
