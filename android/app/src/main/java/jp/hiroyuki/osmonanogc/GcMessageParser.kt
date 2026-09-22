package jp.hiroyuki.osmonanogc

import org.json.JSONObject

object GcMessageParser {
    /** RUNNING=true, any other non-null matchState=false, missing/null/malformed=null. */
    fun runningOrNull(text: String): Boolean? = try {
        val root = JSONObject(text)
        if (!root.has("matchState") || root.isNull("matchState")) null
        else root.optJSONObject("matchState")?.optJSONObject("gameState")
            ?.optString("type", "") == "RUNNING"
    } catch (_: Exception) { null }
}

