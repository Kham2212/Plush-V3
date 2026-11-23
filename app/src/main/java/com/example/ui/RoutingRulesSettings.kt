package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.RoutingAction
import com.example.model.RoutingMatch
import com.example.model.RoutingPreset
import com.example.model.RoutingRule
import java.util.UUID

@Composable
internal fun RoutingRulesSettings(
    rules: List<RoutingRule>,
    onBack: () -> Unit,
    onSaveRule: (RoutingRule) -> Unit,
    onDeleteRule: (String) -> Unit,
) {
    // null closes the editor; an empty id opens a new draft.
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.label_settings), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.routing_rules_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.routing_rules_help), modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { editorId = "" }) { Text(stringResource(R.string.routing_add)) }
                if (rules.isEmpty()) {
                    Text(stringResource(R.string.routing_empty), modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        val preset = rule.preset
                        val title = when (preset) {
                            RoutingPreset.LAN_BYPASS -> stringResource(R.string.routing_preset_lan)
                            RoutingPreset.BLOCK_UDP_443 -> stringResource(R.string.routing_preset_udp)
                            RoutingPreset.PROXY_GOOGLE -> stringResource(R.string.routing_preset_google)
                            RoutingPreset.BYPASS_CN_PRIVATE_DNS -> stringResource(R.string.routing_preset_cn)
                            null -> rule.value
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(
                                        R.string.routing_rule_summary,
                                        stringResource(rule.action.label()),
                                        stringResource(rule.match.label()),
                                    ),
                                )
                            }
                            val toggleLabel = stringResource(R.string.routing_rule_toggle, title)
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { onSaveRule(rule.copy(enabled = it)) },
                                modifier = Modifier.semantics { contentDescription = toggleLabel },
                            )
                        }
                        if (preset != null) {
                            Text(
                                if (rule.match == RoutingMatch.UDP_PORT)
                                    stringResource(R.string.routing_preset_udp_match, rule.value)
                                else rule.value,
                            )
                        }
                        Row {
                            TextButton(onClick = { editorId = rule.id }) {
                                Text(stringResource(R.string.routing_edit))
                            }
                            TextButton(onClick = { onDeleteRule(rule.id) }) {
                                Text(stringResource(R.string.routing_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    editorId?.let { id ->
        val existing = rules.firstOrNull { it.id == id }
        if (id.isEmpty() || existing != null) key(id) {
            RoutingRuleEditor(
                existing = existing,
                onClose = { editorId = null },
                onSave = {
                    onSaveRule(it)
                    editorId = null
                },
            )
        }
    }
}

@Composable
private fun RoutingRuleEditor(
    existing: RoutingRule?,
    onClose: () -> Unit,
    onSave: (RoutingRule) -> Unit,
) {
    var match by rememberSaveable { mutableStateOf(existing?.match ?: RoutingMatch.DOMAIN) }
    var action by rememberSaveable { mutableStateOf(existing?.action ?: RoutingAction.PROXY) }
    var input by rememberSaveable { mutableStateOf(existing?.value.orEmpty()) }
    val value = match.normalize(input)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (existing == null) R.string.routing_add else R.string.routing_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RuleSelector(R.string.routing_action, action, RoutingAction.entries, { it.label() }) { action = it }
                RuleSelector(R.string.routing_match, match, RoutingMatch.entries, { it.label() }) { match = it }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.routing_destination)) },
                    supportingText = {
                        Text(stringResource(when (match) {
                            RoutingMatch.DOMAIN -> R.string.routing_domain_help
                            RoutingMatch.IP -> R.string.routing_ip_help
                            RoutingMatch.PORT, RoutingMatch.UDP_PORT -> R.string.routing_port_help
                            RoutingMatch.DOMAIN_OR_IP -> R.string.routing_mixed_help
                        }))
                    },
                    isError = input.isNotBlank() && value == null,
                    singleLine = match == RoutingMatch.PORT || match == RoutingMatch.UDP_PORT,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = {
                    value?.let {
                        onSave(RoutingRule(existing?.id ?: UUID.randomUUID().toString(), action, match, it, existing?.enabled ?: true))
                    }
                },
            ) { Text(stringResource(R.string.routing_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.routing_cancel)) } },
    )
}

@Composable
private fun <T> RuleSelector(
    @StringRes title: Int,
    selected: T,
    choices: List<T>,
    label: (T) -> Int,
    onChange: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.routing_selector, stringResource(title), stringResource(label(selected))))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(label(choice))) },
                    onClick = {
                        expanded = false
                        onChange(choice)
                    },
                )
            }
        }
    }
}

@StringRes
private fun RoutingAction.label() = when (this) {
    RoutingAction.PROXY -> R.string.routing_proxy
    RoutingAction.DIRECT -> R.string.routing_direct
    RoutingAction.BLOCK -> R.string.routing_block
}

@StringRes
private fun RoutingMatch.label() = when (this) {
    RoutingMatch.DOMAIN -> R.string.routing_domain
    RoutingMatch.IP -> R.string.routing_ip
    RoutingMatch.PORT -> R.string.routing_port
    RoutingMatch.UDP_PORT -> R.string.routing_udp_port
    RoutingMatch.DOMAIN_OR_IP -> R.string.routing_mixed
}
