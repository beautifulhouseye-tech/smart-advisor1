package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.FollowUpEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.ui.viewmodel.MenuFollowUpViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MenuFollowUpViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val followUps by viewModel.followUps.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("رسائل المتابعة التلقائية") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("follow_up_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_followup_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "إضافة متابعة"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_followup_button")
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "إضافة متابعة")
                        Text("إضافة متابعة", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                if (followUps.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ScheduleSend,
                                    contentDescription = "لا توجد متابعات",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "لا توجد رسائل متابعة مضافة",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "أضف رسالة متابعة تُرسل تلقائياً بعد دقائق محددة من تفعيل قاعدة رد.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(followUps, key = { it.id }) { fu ->
                        val linkedRule = rules.find { it.id == fu.ruleId }
                        FollowUpCard(
                            followUp = fu,
                            ruleKeyword = linkedRule?.keyword ?: "قاعدة #${fu.ruleId}",
                            onToggle = { enabled -> viewModel.toggleFollowUp(fu, enabled) },
                            onDelete = { viewModel.deleteFollowUp(fu) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddFollowUpDialog(
            rules = rules,
            onDismiss = { showAddDialog = false },
            onSave = { ruleId, delayMinutes, template ->
                viewModel.saveFollowUp(ruleId, delayMinutes, template)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun FollowUpCard(
    followUp: FollowUpEntity,
    ruleKeyword: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = ruleKeyword,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "بعد ${followUp.delayMinutes} د",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = followUp.enabled, onCheckedChange = onToggle)
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف المتابعة",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Text(
                text = followUp.messageTemplate,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFollowUpDialog(
    rules: List<ReplyRuleEntity>,
    onDismiss: () -> Unit,
    onSave: (Long, Int, String) -> Unit
) {
    var selectedRuleId by remember(rules) { mutableLongStateOf(rules.firstOrNull()?.id ?: 0L) }
    var delayText by remember { mutableStateOf("15") }
    var messageTemplate by remember { mutableStateOf("") }
    var rulesExpanded by remember { mutableStateOf(false) }

    val selectedRuleLabel = rules.find { it.id == selectedRuleId }?.keyword ?: "اختر قاعدة"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة متابعة تلقائية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(
                    expanded = rulesExpanded,
                    onExpandedChange = { rulesExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedRuleLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("القاعدة المرتبطة") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = rulesExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .testTag("followup_rule_dropdown")
                    )
                    ExposedDropdownMenu(
                        expanded = rulesExpanded,
                        onDismissRequest = { rulesExpanded = false }
                    ) {
                        rules.forEach { rule ->
                            DropdownMenuItem(
                                text = { Text(rule.keyword) },
                                onClick = {
                                    selectedRuleId = rule.id
                                    rulesExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("التأخير بالدقائق") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("followup_delay_input")
                )

                OutlinedTextField(
                    value = messageTemplate,
                    onValueChange = { messageTemplate = it },
                    label = { Text("نص رسالة المتابعة") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("followup_message_input")
                )
            }
        },
        confirmButton = {
            val delayVal = delayText.toIntOrNull() ?: 0
            Button(
                onClick = { onSave(selectedRuleId, delayVal, messageTemplate) },
                enabled = selectedRuleId > 0L && delayVal > 0 && messageTemplate.isNotBlank(),
                modifier = Modifier.testTag("save_followup_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
