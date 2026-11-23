package com.example.data

import android.content.Context
import androidx.core.content.edit
import com.example.model.RoutingAction
import com.example.model.RoutingMatch
import com.example.model.RoutingPreset
import com.example.model.RoutingRule
import org.json.JSONArray
import org.json.JSONObject

/** Stored separately from downloaded config so sync cannot overwrite personal rules. */
internal class RoutingRulesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("routing_rules", Context.MODE_PRIVATE)

    fun load(): List<RoutingRule> {
        val rows = runCatching { JSONArray(prefs.getString("rules", "[]")) }.getOrElse { JSONArray() }
        val initialized = prefs.getBoolean("presets_initialized", false)
        val saved = (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                val enabled = row.getBoolean("enabled")
                // The old UDP preset was stored as a generic port but was applied as UDP-only.
                val match = if (!initialized && id == RoutingPreset.BLOCK_UDP_443.id) {
                    RoutingMatch.UDP_PORT
                } else {
                    RoutingMatch.valueOf(row.getString("match"))
                }
                RoutingRule(
                    id = id,
                    action = RoutingAction.valueOf(row.getString("action")),
                    match = match,
                    value = row.getString("value"),
                    enabled = enabled,
                ).validated()
            }.getOrNull()
        }
        if (initialized) return saved
        // On upgrade append only missing presets, preserving the order of any saved rules.
        return saved + RoutingPreset.entries.filter { preset -> saved.none { it.id == preset.id } }
            .map { it.rule() }
    }

    fun save(rules: List<RoutingRule>) {
        val rows = JSONArray()
        rules.forEach { rule ->
            rows.put(JSONObject().put("id", rule.id).put("action", rule.action.name)
                .put("match", rule.match.name).put("value", rule.value).put("enabled", rule.enabled))
        }
        prefs.edit {
            putString("rules", rows.toString())
            putBoolean("presets_initialized", true)
        }
    }
}
