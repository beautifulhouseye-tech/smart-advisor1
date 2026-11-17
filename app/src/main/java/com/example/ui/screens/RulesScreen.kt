package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity

private enum class RuleFilterTab(val label: String) {
    ALL("الكل"),
    ENABLED("المفعّلة"),
    DISABLED("المعطّلة")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(
    rules: List<ReplyRuleEntity>,
    onSaveRule: (ReplyRuleEntity) -> Unit,
    onToggleRule: (Long, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onExportJson: (Uri) -> Unit,
    onImportJson: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(RuleFilterTab.ALL) }
    var editingRule by remember { mutableStateOf<ReplyRuleEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportJson) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportJson) }

    val filteredRules = remember(rules, searchQuery, selectedTab) {
        rules.filter { rule ->
            val matchesQuery = searchQuery.isBlank() ||
                rule.keyword.contains(searchQuery, ignoreCase = true) ||
                rule.replyText.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedTab) {
                RuleFilterTab.ALL -> true
                RuleFilterTab.ENABLED -> rule.isEnabled
                RuleFilterTab.DISABLED -> !rule.isEnabled
            }
            matchesQuery && matchesTab
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingRule = null
                    showEditor = true
                },
                modifier = Modifier.testTag("add_rule_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "إضافة قاعدة جديدة"
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "اصنع روبوتك (قواعد الرد)",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    exportLauncher.launch("automate_ai_backup.json")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("rules_export_json_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Upload,
                                    contentDescription = "تصدير JSON",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تصدير JSON")
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("rules_import_json_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = "استيراد JSON",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("استيراد JSON")
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "بحث في القواعد"
                                )
                            },
                            placeholder = { Text("ابحث بالكلمة المفتاحية أو نص الرد...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rules_search_input")
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuleFilterTab.entries.forEach { tab ->
                                FilterChip(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                }

                if (filteredRules.isEmpty()) {
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
                                    imageVector = Icons.AutoMirrored.Filled.Rule,
                                    contentDescription = "لا توجد قواعد",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "لا توجد قواعد مطابقة",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "اضغط على زر + لإضافة قاعدة كلمة مفتاحية ورد مخصص.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredRules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                            onEdit = {
                                editingRule = rule
                                showEditor = true
                            },
                            onDelete = { onDeleteRule(rule.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showEditor) {
        RuleEditorDialog(
            initialRule = editingRule,
            onDismiss = { showEditor = false },
            onSave = { updated ->
                onSaveRule(updated)
                showEditor = false
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: ReplyRuleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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
                            text = if (rule.keyword.trim() == "*") "رد افتراضي (*)" else rule.keyword,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = rule.matchType.labelAr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            Text(
                text = rule.replyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (rule.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT || !rule.imageUri.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = "رد بصورة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when (rule.replyType) {
                            ReplyRuleEntity.REPLY_TYPE_IMAGE -> "نوع الرد: صورة فقط"
                            ReplyRuleEntity.REPLY_TYPE_BOTH -> "نوع الرد: نص + صورة"
                            else -> "نوع الرد: نص"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الأولوية: ${rule.priority} • مرات الاستخدام: ${rule.hitCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "تعديل القاعدة",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف القاعدة",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(
    initialRule: ReplyRuleEntity?,
    onDismiss: () -> Unit,
    onSave: (ReplyRuleEntity) -> Unit
) {
    var keyword by remember(initialRule) { mutableStateOf(initialRule?.keyword ?: "") }
    var replyText by remember(initialRule) { mutableStateOf(initialRule?.replyText ?: "") }
    var matchType by remember(initialRule) { mutableStateOf(initialRule?.matchType ?: MatchType.CONTAINS) }
    var priority by remember(initialRule) { mutableIntStateOf(initialRule?.priority ?: 5) }
    var replyType by remember(initialRule) {
        mutableStateOf(initialRule?.replyType ?: ReplyRuleEntity.REPLY_TYPE_TEXT)
    }
    var imageUri by remember(initialRule) { mutableStateOf(initialRule?.imageUri) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            imageUri = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialRule == null) "إضافة قاعدة رد جديدة" else "تعديل القاعدة",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("الكلمة المفتاحية (افصل بفاصلة أو استخدم *)") },
                    placeholder = { Text("مثال: السلام عليكم, مرحبا") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rule_keyword_input")
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { keyword = "*" },
                        label = { Text("كل الرسائل (*)") }
                    )
                    AssistChip(
                        onClick = { keyword = "السلام عليكم, مرحبا" },
                        label = { Text("تحية") }
                    )
                    AssistChip(
                        onClick = { keyword = "السعر, بكم" },
                        label = { Text("استفسار سعر") }
                    )
                }

                Text(
                    text = "نوع الرد",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ReplyRuleEntity.REPLY_TYPE_TEXT to "نص",
                        ReplyRuleEntity.REPLY_TYPE_IMAGE to "صورة",
                        ReplyRuleEntity.REPLY_TYPE_BOTH to "نص + صورة"
                    ).forEach { (typeKey, label) ->
                        FilterChip(
                            selected = replyType == typeKey,
                            onClick = { replyType = typeKey },
                            label = { Text(label) }
                        )
                    }
                }

                if (replyType != ReplyRuleEntity.REPLY_TYPE_TEXT) {
                    OutlinedButton(
                        onClick = {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_rule_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = "اختيار صورة",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (imageUri.isNullOrBlank()) "اختر صورة من المعرض" else "تغيير الصورة المختارة ✓")
                    }
                }

                if (replyType != ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("نص الرد التلقائي") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rule_reply_input")
                    )
                }

                Text(
                    text = "نوع المطابقة",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchType.entries.forEach { type ->
                        FilterChip(
                            selected = matchType == type,
                            onClick = { matchType = type },
                            label = { Text(type.labelAr) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الأولوية: $priority", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 10).forEach { p ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text("$p") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isValidContent = when (replyType) {
                ReplyRuleEntity.REPLY_TYPE_IMAGE -> !imageUri.isNullOrBlank()
                ReplyRuleEntity.REPLY_TYPE_BOTH -> replyText.isNotBlank() && !imageUri.isNullOrBlank()
                else -> replyText.isNotBlank()
            }
            Button(
                onClick = {
                    val finalReplyText = if (replyType == ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                        replyText.trim().ifBlank { "[صورة]" }
                    } else {
                        replyText.trim()
                    }
                    val entity = (initialRule ?: ReplyRuleEntity(
                        keyword = keyword.trim(),
                        replyText = finalReplyText
                    )).copy(
                        keyword = keyword.trim(),
                        replyText = finalReplyText,
                        matchType = matchType,
                        priority = priority,
                        replyType = replyType,
                        imageUri = if (replyType == ReplyRuleEntity.REPLY_TYPE_TEXT) null else imageUri
                    )
                    onSave(entity)
                },
                enabled = keyword.isNotBlank() && isValidContent,
                modifier = Modifier.testTag("save_rule_button")
            ) {
                Text("حفظ القاعدة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
