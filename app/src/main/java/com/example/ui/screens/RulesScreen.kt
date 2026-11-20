package com.example.ui.screens

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity

private enum class RuleFilterTab(val label: String) {
    ALL("الكل"),
    ENABLED("المفعّلة"),
    DISABLED("المعطّلة")
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    rules: List<ReplyRuleEntity>,
    onSaveRule: (ReplyRuleEntity) -> Unit,
    onToggleRule: (Long, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    templates: List<ReplyTemplateEntity> = emptyList(),
    onNavigateToTemplates: (() -> Unit)? = null,
    onNavigateToMenus: (() -> Unit)? = null,
    onNavigateToFollowUps: (() -> Unit)? = null,
    onSaveAsTemplate: ((ReplyTemplateEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(RuleFilterTab.ALL) }
    var editingRule by remember { mutableStateOf<ReplyRuleEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val templatesById = remember(templates) { templates.associateBy { it.id } }

    val filteredRules = remember(rules, templatesById, searchQuery, selectedTab) {
        rules.filter { rule ->
            val linkedTemplate = rule.templateId?.let { templatesById[it] }
            val effectiveText = linkedTemplate?.body ?: rule.replyText
            val matchesQuery = searchQuery.isBlank() ||
                rule.keyword.contains(searchQuery, ignoreCase = true) ||
                effectiveText.contains(searchQuery, ignoreCase = true) ||
                (linkedTemplate?.title?.contains(searchQuery, ignoreCase = true) == true)
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
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "القواعد والأتمتة",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        },
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
                        if (onNavigateToTemplates != null || onNavigateToMenus != null || onNavigateToFollowUps != null) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (onNavigateToTemplates != null) {
                                    AssistChip(
                                        onClick = onNavigateToTemplates,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Bookmarks,
                                                contentDescription = "القوالب الجاهزة",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = "القوالب الجاهزة (${templates.size})",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("rules_manage_templates_btn")
                                    )
                                }
                                if (onNavigateToMenus != null) {
                                    AssistChip(
                                        onClick = onNavigateToMenus,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.FormatListNumbered,
                                                contentDescription = "القوائم التفاعلية",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = "القوائم التفاعلية",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("rules_manage_menus_btn")
                                    )
                                }
                                if (onNavigateToFollowUps != null) {
                                    AssistChip(
                                        onClick = onNavigateToFollowUps,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ScheduleSend,
                                                contentDescription = "رسائل المتابعة",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = "رسائل المتابعة",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("rules_manage_followups_btn")
                                    )
                                }
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
                            placeholder = { Text("ابحث بالكلمة المفتاحية أو نص الرد أو القالب...") },
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
                            linkedTemplate = rule.templateId?.let { templatesById[it] },
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
            templates = templates,
            onSaveAsTemplate = onSaveAsTemplate,
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
    linkedTemplate: ReplyTemplateEntity?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val displayText = linkedTemplate?.body ?: rule.replyText
    val displayType = linkedTemplate?.replyType ?: rule.replyType
    val displayImageUri = linkedTemplate?.imageUri ?: rule.imageUri

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

            if (linkedTemplate != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = "مرتبط بقالب",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "قالب مرتبط: ${linkedTemplate.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (displayType != ReplyRuleEntity.REPLY_TYPE_TEXT || !displayImageUri.isNullOrBlank()) {
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
                        text = when (displayType) {
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
    templates: List<ReplyTemplateEntity>,
    onSaveAsTemplate: ((ReplyTemplateEntity) -> Unit)?,
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
    var linkedTemplateId by remember(initialRule) { mutableStateOf(initialRule?.templateId) }

    var showTemplatePicker by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }

    val linkedTemplate = remember(linkedTemplateId, templates) {
        linkedTemplateId?.let { tid -> templates.firstOrNull { it.id == tid } }
    }

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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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

                if (templates.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showTemplatePicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("rule_pick_template_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdded,
                            contentDescription = "اختيار من القوالب",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اختيار من القوالب المحفوظة (${templates.size})")
                    }
                }

                if (linkedTemplate != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Link,
                                        contentDescription = "ربط حي بالقالب",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "مرتبط حيّاً: ${linkedTemplate.title}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        replyText = linkedTemplate.body
                                        replyType = linkedTemplate.replyType
                                        imageUri = linkedTemplate.imageUri
                                        linkedTemplateId = null
                                    },
                                    modifier = Modifier.testTag("rule_unlink_template_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = "فك الربط",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("فك الربط")
                                }
                            }
                            Text(
                                text = linkedTemplate.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "أي تعديل تجريه لاحقاً على هذا القالب سيُحدّث رد هذه القاعدة تلقائياً.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                } else {
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

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TEMPLATE_VARIABLE_HINTS.forEach { (token, label) ->
                                AssistChip(
                                    onClick = {
                                        val sep = if (replyText.isNotEmpty() && !replyText.endsWith(" ") && !replyText.endsWith("\n")) " " else ""
                                        replyText = "$replyText$sep$token"
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    if (onSaveAsTemplate != null && (replyText.isNotBlank() || !imageUri.isNullOrBlank())) {
                        TextButton(
                            onClick = { showSaveTemplateDialog = true },
                            modifier = Modifier.testTag("rule_save_as_template_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkAdded,
                                contentDescription = "حفظ كقالب جديد",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("حفظ هذا الرد كقالب لإعادة الاستخدام")
                        }
                    }
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
            val effectiveReplyType = linkedTemplate?.replyType ?: replyType
            val effectiveImageUri = linkedTemplate?.imageUri ?: imageUri
            val effectiveReplyText = linkedTemplate?.body ?: replyText
            val isValidContent = if (linkedTemplate != null) {
                true
            } else {
                when (effectiveReplyType) {
                    ReplyRuleEntity.REPLY_TYPE_IMAGE -> !effectiveImageUri.isNullOrBlank()
                    ReplyRuleEntity.REPLY_TYPE_BOTH -> effectiveReplyText.isNotBlank() && !effectiveImageUri.isNullOrBlank()
                    else -> effectiveReplyText.isNotBlank()
                }
            }
            Button(
                onClick = {
                    val finalReplyText = if (effectiveReplyType == ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                        effectiveReplyText.trim().ifBlank { "[صورة]" }
                    } else {
                        effectiveReplyText.trim()
                    }
                    val entity = (initialRule ?: ReplyRuleEntity(
                        keyword = keyword.trim(),
                        replyText = finalReplyText
                    )).copy(
                        keyword = keyword.trim(),
                        replyText = finalReplyText,
                        matchType = matchType,
                        priority = priority,
                        replyType = effectiveReplyType,
                        imageUri = if (effectiveReplyType == ReplyRuleEntity.REPLY_TYPE_TEXT) null else effectiveImageUri,
                        templateId = linkedTemplate?.id
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

    if (showTemplatePicker) {
        TemplatePickerDialog(
            templates = templates,
            onDismiss = { showTemplatePicker = false },
            onLinkTemplate = { tpl ->
                linkedTemplateId = tpl.id
                replyText = tpl.body
                replyType = tpl.replyType
                imageUri = tpl.imageUri
                showTemplatePicker = false
            },
            onCopyTemplate = { tpl ->
                linkedTemplateId = null
                replyText = tpl.body
                replyType = tpl.replyType
                imageUri = tpl.imageUri
                showTemplatePicker = false
            }
        )
    }

    if (showSaveTemplateDialog && onSaveAsTemplate != null) {
        val suggestedTitle = keyword.trim()
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?: "قالب مخصص"
        TemplateEditorDialog(
            initialTemplate = ReplyTemplateEntity(
                title = suggestedTitle,
                category = ReplyTemplateEntity.CATEGORY_GENERAL,
                body = replyText.trim(),
                replyType = replyType,
                imageUri = if (replyType == ReplyRuleEntity.REPLY_TYPE_TEXT) null else imageUri
            ),
            onDismiss = { showSaveTemplateDialog = false },
            onSave = { newTemplate ->
                onSaveAsTemplate(newTemplate)
                showSaveTemplateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplatePickerDialog(
    templates: List<ReplyTemplateEntity>,
    onDismiss: () -> Unit,
    onLinkTemplate: (ReplyTemplateEntity) -> Unit,
    onCopyTemplate: (ReplyTemplateEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("الكل") }

    val categories = remember(templates) {
        listOf("الكل") + templates.map { it.category }.filter { it.isNotBlank() }.distinct()
    }
    val filtered = remember(templates, search, selectedCategory) {
        templates.filter { tpl ->
            val catOk = selectedCategory == "الكل" || tpl.category == selectedCategory
            val qOk = search.isBlank() ||
                tpl.title.contains(search, ignoreCase = true) ||
                tpl.body.contains(search, ignoreCase = true)
            catOk && qOk
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "اختر قالباً للقاعدة",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("ابحث في القوالب...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "بحث"
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("template_picker_search")
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { tpl ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tpl.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = tpl.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = tpl.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { onLinkTemplate(tpl) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                            .testTag("pick_link_template_${tpl.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Link,
                                            contentDescription = "ربط حي",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("ربط حي", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    OutlinedButton(
                                        onClick = { onCopyTemplate(tpl) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                            .testTag("pick_copy_template_${tpl.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "إدراج نسخة",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إدراج نسخة", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}
