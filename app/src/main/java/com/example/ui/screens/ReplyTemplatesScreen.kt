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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.ReplyRuleEntity
import com.example.data.local.entity.ReplyTemplateEntity
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine

internal val TEMPLATE_VARIABLE_HINTS = listOf(
    "{{sender_name}}" to "اسم المرسل",
    "{{phone}}" to "رقم الهاتف",
    "{{message}}" to "نص الرسالة",
    "{{group_name}}" to "اسم المجموعة"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReplyTemplatesScreen(
    templates: List<ReplyTemplateEntity>,
    linkedCountsByTemplateId: Map<Long, Int> = emptyMap(),
    onSaveTemplate: (ReplyTemplateEntity) -> Unit,
    onDuplicateTemplate: (ReplyTemplateEntity) -> Unit,
    onDeleteTemplate: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("الكل") }
    var editingTemplate by remember { mutableStateOf<ReplyTemplateEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val availableCategories = remember(templates) {
        val custom = templates.map { it.category }.filter { it.isNotBlank() }
        listOf("الكل") + (ReplyTemplateEntity.DEFAULT_CATEGORIES + custom).distinct()
    }

    val filteredTemplates = remember(templates, searchQuery, selectedCategory) {
        templates.filter { tpl ->
            val matchesCategory = selectedCategory == "الكل" || tpl.category == selectedCategory
            val matchesQuery = searchQuery.isBlank() ||
                tpl.title.contains(searchQuery, ignoreCase = true) ||
                tpl.body.contains(searchQuery, ignoreCase = true) ||
                tpl.category.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("قوالب الردود الجاهزة") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("templates_back_button")
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
                onClick = {
                    editingTemplate = null
                    showEditor = true
                },
                modifier = Modifier.testTag("add_template_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "إضافة قالب جديد"
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "مكتبة الردود المحفوظة",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "احفظ ردودك المتكررة مع متغيرات ذكية واربطها بالقواعد للتحديث التلقائي من مكان واحد.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(
                                    text = "${templates.size} قالب",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "بحث في القوالب"
                                )
                            },
                            placeholder = { Text("ابحث باسم القالب أو نصه أو تصنيفه...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("templates_search_input")
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            availableCategories.forEach { cat ->
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = { selectedCategory = cat },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }
                }

                if (filteredTemplates.isEmpty()) {
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
                                    imageVector = Icons.Filled.BookmarkAdded,
                                    contentDescription = "لا توجد قوالب",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "لا توجد قوالب مطابقة",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "اضغط على زر + لإنشاء قالب رد جاهز يمكن استخدامه في أي قاعدة.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredTemplates, key = { it.id }) { template ->
                        TemplateCard(
                            template = template,
                            linkedRulesCount = linkedCountsByTemplateId[template.id] ?: 0,
                            onEdit = {
                                editingTemplate = template
                                showEditor = true
                            },
                            onDuplicate = { onDuplicateTemplate(template) },
                            onDelete = { onDeleteTemplate(template.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showEditor) {
        TemplateEditorDialog(
            initialTemplate = editingTemplate,
            onDismiss = { showEditor = false },
            onSave = { updated ->
                onSaveTemplate(updated)
                showEditor = false
            }
        )
    }
}

@Composable
private fun TemplateCard(
    template: ReplyTemplateEntity,
    linkedRulesCount: Int,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template_card_${template.id}"),
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
                Text(
                    text = template.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = template.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = template.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (template.replyType != ReplyRuleEntity.REPLY_TYPE_TEXT || !template.imageUri.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = "مرفق صورة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when (template.replyType) {
                            ReplyRuleEntity.REPLY_TYPE_IMAGE -> "صورة فقط"
                            ReplyRuleEntity.REPLY_TYPE_BOTH -> "نص + صورة"
                            else -> "نص"
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
                    text = "قواعد مرتبطة: $linkedRulesCount • مرات الإرسال: ${template.usageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier.testTag("duplicate_template_${template.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "نسخ القالب",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_template_${template.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "تعديل القالب",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_template_${template.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف القالب",
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
fun TemplateEditorDialog(
    initialTemplate: ReplyTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (ReplyTemplateEntity) -> Unit
) {
    var title by remember(initialTemplate) { mutableStateOf(initialTemplate?.title ?: "") }
    var category by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.category ?: ReplyTemplateEntity.CATEGORY_GENERAL)
    }
    var body by remember(initialTemplate) { mutableStateOf(initialTemplate?.body ?: "") }
    var replyType by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.replyType ?: ReplyRuleEntity.REPLY_TYPE_TEXT)
    }
    var imageUri by remember(initialTemplate) { mutableStateOf(initialTemplate?.imageUri) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            imageUri = uri.toString()
        }
    }

    val samplePreview = remember(body) {
        if (body.isBlank()) ""
        else VariableEngine.resolveTemplate(
            body,
            IncomingMessage(
                app = "com.whatsapp",
                sender = "أحمد",
                senderName = "أحمد",
                message = "كم سعر التوصيل؟",
                phone = "967777123456",
                groupName = "الدعم السريع",
                isGroup = false,
                notificationId = "preview"
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialTemplate == null) "إضافة قالب رد جديد" else "تعديل القالب",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("اسم القالب") },
                    placeholder = { Text("مثال: ترحيب بالعملاء الجدد") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("template_title_input")
                )

                Text(
                    text = "التصنيف",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReplyTemplateEntity.DEFAULT_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
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
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = "اختيار صورة للقالب",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (imageUri.isNullOrBlank()) "اختر صورة من المعرض" else "تغيير الصورة المختارة ✓")
                    }
                }

                if (replyType != ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("نص القالب") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template_body_input")
                    )

                    Text(
                        text = "إدراج متغيرات تلقائية",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TEMPLATE_VARIABLE_HINTS.forEach { (token, label) ->
                            AssistChip(
                                onClick = {
                                    val sep = if (body.isNotEmpty() && !body.endsWith(" ") && !body.endsWith("\n")) " " else ""
                                    body = "$body$sep$token"
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    if (samplePreview.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "معاينة تجريبية:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = samplePreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isValidContent = when (replyType) {
                ReplyRuleEntity.REPLY_TYPE_IMAGE -> !imageUri.isNullOrBlank()
                ReplyRuleEntity.REPLY_TYPE_BOTH -> body.isNotBlank() && !imageUri.isNullOrBlank()
                else -> body.isNotBlank()
            }
            Button(
                onClick = {
                    val finalBody = if (replyType == ReplyRuleEntity.REPLY_TYPE_IMAGE) {
                        body.trim().ifBlank { "[صورة]" }
                    } else {
                        body.trim()
                    }
                    val entity = (initialTemplate ?: ReplyTemplateEntity(
                        title = title.trim(),
                        category = category,
                        body = finalBody
                    )).copy(
                        title = title.trim(),
                        category = category,
                        body = finalBody,
                        replyType = replyType,
                        imageUri = if (replyType == ReplyRuleEntity.REPLY_TYPE_TEXT) null else imageUri
                    )
                    onSave(entity)
                },
                enabled = title.isNotBlank() && isValidContent,
                modifier = Modifier.testTag("save_template_button")
            ) {
                Text("حفظ القالب")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
