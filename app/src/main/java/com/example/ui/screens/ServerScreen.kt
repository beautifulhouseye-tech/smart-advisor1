package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.FieldType
import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.WebhookMode
import com.example.ui.components.BidiDisplayFormatter
import com.example.ui.components.UnifiedTestResultCard
import com.example.ui.viewmodel.ServerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val configState by viewModel.config.collectAsStateWithLifecycle()
    val previewJson by viewModel.previewJson.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveStatus) {
        saveStatus?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSaveStatus()
        }
    }

    val config = configState
    val headers = remember(config?.headersJson) {
        viewModel.decodeHeaders(config?.headersJson.orEmpty())
    }
    val fields = remember(config?.requestFieldsJson) {
        viewModel.decodeFields(config?.requestFieldsJson.orEmpty())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("خادم الويب هوك") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("server_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::loadDefaults,
                        modifier = Modifier.testTag("server_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = "استعادة الافتراضي"
                        )
                    }
                    TextButton(
                        onClick = viewModel::save,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("server_save_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("احفظ")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (config == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val currentGroupMode = remember(config.sendOnGroup) {
            runCatching { GroupReplyMode.valueOf(config.sendOnGroup) }
                .getOrDefault(GroupReplyMode.KEYWORD_ONLY)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Activation Switch + Basic Server Configuration
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Cloud,
                                        contentDescription = "خادم الويب هوك",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            text = "تفعيل خادم الويب هوك (Webhook)",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "منفذ للخدمات الخارجية (CRM / ERP / الشحنات) — يعمل كأداة بيد AI أو كمرسل مستقل عند إيقاف AI.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = config.isEnabled,
                                    onCheckedChange = viewModel::toggleEnabled,
                                    modifier = Modifier.testTag("server_enabled_switch")
                                )
                            }

                            val currentWebhookMode = remember(config.webhookMode) {
                                config.effectiveWebhookMode()
                            }
                            SimpleDropdownSelector(
                                label = "وضع تشغيل Webhook",
                                selected = currentWebhookMode.name,
                                options = WebhookMode.entries.map { it.name },
                                optionLabel = { webhookModeLabel(WebhookMode.valueOf(it)) },
                                onSelect = { selected ->
                                    viewModel.updateWebhookMode(WebhookMode.valueOf(selected))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_webhook_mode_dropdown")
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (currentWebhookMode == WebhookMode.AI_TOOL) {
                                        "وضع أداة AI: يستدعي الذكاء الاصطناعي الخادم عند الحاجة فقط، ولا يُسمح للخادم بإرسال رد مستقل للعميل."
                                    } else {
                                        "الوضع المستقل: يعمل فقط عندما يكون منسّق AI متوقفاً؛ وعند تفعيل AI يتم تعطيل الإرسال المستقل تلقائياً منعاً للتضارب."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            OutlinedTextField(
                                value = config.toolDescription,
                                onValueChange = viewModel::updateToolDescription,
                                label = { Text("وصف الأداة للذكاء الاصطناعي (متى يستدعيها؟)") },
                                placeholder = { Text("مثال: التحقق من حالة الشحنات والفواتير من نظام CRM") },
                                singleLine = false,
                                minLines = 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_tool_description_input")
                            )

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = config.url,
                                    onValueChange = viewModel::updateUrl,
                                    label = { Text("رابط الخادم (HTTPS URL)") },
                                    placeholder = { Text("https://www.777736225.com/wp-json/smart-advisor/v1/whatsapp/reply") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("server_url_input")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SimpleDropdownSelector(
                                    label = "الطريقة (Method)",
                                    selected = config.method,
                                    options = listOf("POST", "GET", "PUT", "PATCH"),
                                    onSelect = viewModel::updateMethod,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("server_method_dropdown")
                                )
                                SimpleDropdownSelector(
                                    label = "نوع المحتوى",
                                    selected = config.contentType,
                                    options = listOf("JSON", "FORM", "TEXT"),
                                    onSelect = viewModel::updateContentType,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("server_content_type_dropdown")
                                )
                            }

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = config.timeoutSeconds.toString(),
                                        onValueChange = { raw ->
                                            val parsed = raw.filter { it.isDigit() }.toIntOrNull()
                                            if (parsed != null) viewModel.updateTimeout(parsed)
                                        },
                                        label = { Text("المهلة (ثانية)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("server_timeout_input")
                                    )
                                    OutlinedTextField(
                                        value = config.retryCount.toString(),
                                        onValueChange = { raw ->
                                            val parsed = raw.filter { it.isDigit() }.toIntOrNull()
                                            if (parsed != null) viewModel.updateRetry(parsed)
                                        },
                                        label = { Text("إعادة المحاولة") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("server_retry_input")
                                    )
                                }
                            }

                            SimpleDropdownSelector(
                                label = "الرد في المجموعات",
                                selected = currentGroupMode.name,
                                options = GroupReplyMode.entries.map { it.name },
                                optionLabel = { groupModeLabel(GroupReplyMode.valueOf(it)) },
                                onSelect = { selected ->
                                    viewModel.updateGroupMode(GroupReplyMode.valueOf(selected))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_group_mode_dropdown")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "الإرسال في المحادثات الخاصة",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Switch(
                                    checked = config.sendOnPrivate,
                                    onCheckedChange = viewModel::updatePrivate,
                                    modifier = Modifier.testTag("server_private_switch")
                                )
                            }
                        }
                    }
                }

                // Headers Section
                item {
                    SectionHeaderRow(
                        title = "ترويسات الطلب (Headers)",
                        actionLabel = "إضافة رأس",
                        actionTag = "add_header_button",
                        onAction = viewModel::addHeader
                    )
                }

                if (headers.isEmpty()) {
                    item {
                        EmptySectionHint(text = "لا توجد ترويسات مضافة بعد. اضغط \"إضافة رأس\" لإضافة ترويسة مصادقة أو مخصصة.")
                    }
                } else {
                    itemsIndexed(headers, key = { index, item -> "hdr_${index}_${item.key}" }) { index, item ->
                        HeaderEditorRow(
                            item = item,
                            hasStoredSecret = item.isSecret && viewModel.hasStoredSecret(item.valueRef),
                            onUpdate = { key, ref, isSecret ->
                                viewModel.updateHeader(index, key, ref, isSecret)
                            },
                            onSecretChange = { secret ->
                                viewModel.setHeaderSecretValue(index, secret)
                            },
                            onDelete = { viewModel.removeHeader(index) }
                        )
                    }
                }

                // Request Fields Section + Variable Help
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeaderRow(
                            title = "حقول الطلب (Request Fields)",
                            actionLabel = "إضافة حقل",
                            actionTag = "add_field_button",
                            onAction = viewModel::addField
                        )
                        VariableHelpCard()
                    }
                }

                if (fields.isEmpty()) {
                    item {
                        EmptySectionHint(text = "لا توجد حقول طلب. أضف حقلاً لتضمين بيانات الرسالة في الطلب.")
                    }
                } else {
                    itemsIndexed(fields, key = { index, f -> "fld_${index}_${f.key}" }) { index, field ->
                        RequestFieldCard(
                            field = field,
                            canMoveUp = index > 0,
                            canMoveDown = index < fields.lastIndex,
                            onToggle = { enabled -> viewModel.toggleField(index, enabled) },
                            onUpdate = { key, template, type, desc ->
                                viewModel.updateField(index, key, template, type, desc)
                            },
                            onMoveUp = { viewModel.moveFieldUp(index) },
                            onMoveDown = { viewModel.moveFieldDown(index) },
                            onDelete = { viewModel.removeField(index) }
                        )
                    }
                }

                // Response Reply Key Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "مفتاح استخراج الرد (Response Reply Key)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = config.responseReplyKey,
                                    onValueChange = viewModel::updateReplyKey,
                                    label = { Text("مسار الرد في استجابة JSON") },
                                    placeholder = { Text("reply أو data.reply أو choices[0].text") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("server_reply_key_input")
                                )
                            }
                            Text(
                                text = "يدعم المسارات المتداخلة مثل data.reply ومؤشرات المصفوفات مثل choices[0].text.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Live Preview Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Code,
                                    contentDescription = "معاينة الطلب",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "معاينة جسم الطلب (${config.contentType})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            val formattedPreview = remember(previewJson, config.contentType) {
                                if (config.contentType.equals("JSON", ignoreCase = true)) {
                                    BidiDisplayFormatter.formatJsonForDisplay(previewJson)
                                } else {
                                    BidiDisplayFormatter.formatCodeExcerptForLtr(previewJson.ifBlank { "{}" })
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = formattedPreview,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                textDirection = TextDirection.Ltr
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            softWrap = false,
                                            modifier = Modifier.testTag("server_preview_text")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Test Request + Tool Preview + Result Card
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = viewModel::testRequest,
                                enabled = !isTesting,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("test_webhook_button")
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("جارٍ الإرسال...")
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "اختبار الاتصال",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("اختبار الاتصال", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.testAsAiTool() },
                                enabled = !isTesting,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("test_webhook_tool_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Code,
                                    contentDescription = "اختبار نتيجة الأداة",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("اختبار الأداة", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        AnimatedVisibility(visible = testResult != null) {
                            testResult?.let { result ->
                                UnifiedTestResultCard(
                                    model = result.toUnifiedCardModel(),
                                    cardTestTag = "test_result_card"
                                )
                            }
                        }
                    }
                }

                // How it works Card
                item {
                    HowItWorksCard()
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        FilledTonalButton(
            onClick = onAction,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(actionTag)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = actionLabel,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun EmptySectionHint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun HeaderEditorRow(
    item: HeaderItem,
    hasStoredSecret: Boolean,
    onUpdate: (key: String, valueRef: String, isSecret: Boolean) -> Unit,
    onSecretChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    var secretInput by remember(item.valueRef) { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = item.key,
                    onValueChange = { onUpdate(it, item.valueRef, item.isSecret) },
                    label = { Text("اسم الرأس (Header)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.isSecret,
                        onCheckedChange = { checked ->
                            onUpdate(item.key, item.valueRef, checked)
                        }
                    )
                    Text("سري", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "حذف الرأس",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (item.isSecret) {
                OutlinedTextField(
                    value = item.valueRef,
                    onValueChange = { onUpdate(item.key, it, true) },
                    label = { Text("مرجع الخزنة (valueRef)") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "مرجع مشفر",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = {
                        secretInput = it
                        onSecretChange(it)
                    },
                    label = {
                        Text(
                            if (hasStoredSecret) "قيمة السر (محفوظة مشفرة — اكتب للتحديث)"
                            else "قيمة السر (تُحفظ مشفرة في SecretVault)"
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showSecret) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "إظهار أو إخفاء السر"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = item.valueRef,
                    onValueChange = { onUpdate(item.key, it, false) },
                    label = { Text("القيمة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableHelpCard() {
    val variables = remember {
        listOf(
            "{{message}}" to "نص الرسالة",
            "{{ai_query}}" to "استعلام AI للأداة",
            "{{sender}}" to "المرسل",
            "{{sender_name}}" to "اسم المرسل",
            "{{phone}}" to "رقم الهاتف",
            "{{app}}" to "التطبيق",
            "{{group_name}}" to "اسم المجموعة",
            "{{is_group}}" to "مجموعة؟",
            "{{timestamp}}" to "الوقت",
            "{{notification_id}}" to "معرّف الإشعار"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "المتغيرات المتاحة داخل القوالب",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                variables.forEach { (token, desc) ->
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "$token • $desc",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestFieldCard(
    field: RequestField,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: (key: String, template: String, type: FieldType, description: String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = field.enabled,
                        onCheckedChange = onToggle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = field.key.ifBlank { "حقل جديد" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "تحريك للأعلى"
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "تحريك للأسفل"
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف الحقل",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = field.key,
                    onValueChange = { onUpdate(it, field.valueTemplate, field.type, field.description) },
                    label = { Text("اسم المفتاح (Key)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                SimpleDropdownSelector(
                    label = "النوع",
                    selected = field.type.name,
                    options = FieldType.entries.map { it.name },
                    onSelect = { selected ->
                        onUpdate(
                            field.key,
                            field.valueTemplate,
                            FieldType.valueOf(selected),
                            field.description
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = field.valueTemplate,
                onValueChange = { onUpdate(field.key, it, field.type, field.description) },
                label = { Text("قالب القيمة (Template)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = field.description,
                onValueChange = { onUpdate(field.key, field.valueTemplate, field.type, it) },
                label = { Text("الوصف (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "كيف يعمل",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "دور خادم Webhook في المنظومة الموحدة",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = "1. في وضع أداة AI (الموصى به): يستدعي الذكاء الاصطناعي الخادم عند حاجته لبيانات خارجية (CRM/ERP/شحنات)، وتصل النتيجة منقحة ومحدودة للـ AI لصياغة رد واحد متماسك.\n" +
                    "2. في الوضع المستقل: يعمل Webhook كمرسل بديل فقط عندما يكون منسّق AI متوقفاً، ويُمنع الإرسال المزدوج أثناء تشغيل AI.\n" +
                    "3. تحفظ الترويسات السرية بأمان داخل SecretVault مع إرسال Idempotency-Key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdownSelector(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private fun webhookModeLabel(mode: WebhookMode): String = when (mode) {
    WebhookMode.AI_TOOL -> "أداة للذكاء الاصطناعي (AI Tool Mode)"
    WebhookMode.STANDALONE_FALLBACK -> "مرسل مستقل عند إيقاف AI (Standalone)"
}

private fun groupModeLabel(mode: GroupReplyMode): String = when (mode) {
    GroupReplyMode.ALL -> "الرد على جميع رسائل المجموعات (ALL)"
    GroupReplyMode.MENTION_ONLY -> "عند الإشارة فقط (MENTION_ONLY)"
    GroupReplyMode.KEYWORD_ONLY -> "عند تطابق كلمة مفتاحية (KEYWORD_ONLY)"
    GroupReplyMode.NEVER -> "عدم الرد في المجموعات (NEVER)"
}
