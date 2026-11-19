package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.AiSettingsState
import com.example.data.preferences.ReplyLanguage
import com.example.data.preferences.ResponseLength
import com.example.data.preferences.ResponseTone
import com.example.network.ai.AIReplyEngine
import com.example.ui.viewmodel.AISettingsViewModel
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AISettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AISettingsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var providerExpanded by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var newApiKeyVisible by remember { mutableStateOf(false) }
    var ollamaKeyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("إعدادات الذكاء الاصطناعي") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("ai_settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Master Enable Switch
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "تفعيل المنسّق الذكي الوحيد (AI Orchestrator)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "AI هو عقل المحادثة الوحيد: يدير الحوار عبر New API مع احتياطي Ollama/Qwen، ويستدعي Smart Advisor (بيانات المتجر) وWebhook (خدمات خارجية) كأدوات عند الحاجة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = viewModel::setEnabled,
                                modifier = Modifier.testTag("ai_enabled_switch")
                            )
                        }
                    }
                }

                // 2. Primary AI Engine / New API Gateway
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "المحرك الرئيسي (New API / بوابة AI)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            ExposedDropdownMenuBox(
                                expanded = providerExpanded,
                                onExpandedChange = { providerExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = state.provider.labelAr,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("مزود الذكاء الرئيسي") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .testTag("ai_provider_dropdown")
                                )
                                ExposedDropdownMenu(
                                    expanded = providerExpanded,
                                    onDismissRequest = { providerExpanded = false }
                                ) {
                                    AIReplyEngine.Provider.entries.forEach { provider ->
                                        DropdownMenuItem(
                                            text = { Text(provider.labelAr) },
                                            onClick = {
                                                viewModel.selectProvider(provider)
                                                providerExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = state.primaryBaseUrl,
                                onValueChange = viewModel::updatePrimaryBaseUrl,
                                label = { Text("رابط بوابة New API (HTTPS)") },
                                placeholder = { Text("https://api.yourdomain.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_primary_base_url_input")
                            )

                            OutlinedTextField(
                                value = state.newApiKey.ifBlank { state.apiKey },
                                onValueChange = { value ->
                                    if (state.provider == AIReplyEngine.Provider.NEW_API || state.primaryBaseUrl.isNotBlank()) {
                                        viewModel.updateNewApiKey(value)
                                    }
                                    viewModel.updateApiKey(value)
                                },
                                label = { Text("مفتاح API الرئيسي (SecretVault)") },
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible || newApiKeyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        apiKeyVisible = !apiKeyVisible
                                        newApiKeyVisible = apiKeyVisible
                                    }) {
                                        Icon(
                                            imageVector = if (apiKeyVisible) {
                                                Icons.Filled.VisibilityOff
                                            } else {
                                                Icons.Filled.Visibility
                                            },
                                            contentDescription = if (apiKeyVisible) "إخفاء المفتاح" else "إظهار المفتاح"
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_api_key_input")
                            )

                            OutlinedTextField(
                                value = state.primaryModel.ifBlank { state.model },
                                onValueChange = { modelName ->
                                    viewModel.updatePrimaryModel(modelName)
                                    viewModel.selectModel(modelName)
                                },
                                label = { Text("اسم النموذج الرئيسي (قابل للتعديل)") },
                                placeholder = { Text("gpt-4o-mini") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_primary_model_input")
                            )

                            Column {
                                Text(
                                    text = "درجة الإبداع (Temperature): ${
                                        String.format(Locale.US, "%.2f", state.temperature)
                                    }",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Slider(
                                    value = state.temperature,
                                    onValueChange = viewModel::updateTemperature,
                                    valueRange = 0f..1f,
                                    steps = 9,
                                    modifier = Modifier.testTag("ai_temperature_slider")
                                )
                            }

                            OutlinedTextField(
                                value = state.maxTokens.toString(),
                                onValueChange = viewModel::updateMaxTokens,
                                label = { Text("الحد الأقصى للرموز MaxTokens (50 - 1200)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_max_tokens_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "حالة الرئيسي: ${state.lastPrimaryStatus}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = viewModel::testAiReply,
                                    enabled = !state.isTesting,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("ai_test_button")
                                ) {
                                    if (state.isTesting) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Psychology,
                                            contentDescription = "اختبار الرئيسي",
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                    }
                                    Text("اختبار New API")
                                }
                            }

                            state.testResult?.let { resultText ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "نتيجة اختبار الرئيسي: $resultText",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Fallback Engine: Ollama / Qwen
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "المحرك الاحتياطي (Ollama / Qwen)",
                                style = MaterialTheme.typography.titleMedium
                            )

                            SettingSwitchRow(
                                title = "تفعيل التحويل التلقائي للاحتياطي",
                                subtitle = "ينتقل تلقائياً إلى نموذج Qwen عند تعطل أو مهلة المحرك الرئيسي.",
                                checked = state.fallbackEnabled,
                                onCheckedChange = viewModel::setFallbackEnabled,
                                testTag = "ai_fallback_enabled_switch"
                            )

                            SettingSwitchRow(
                                title = "تشغيل نموذج Qwen عبر بوابة New API",
                                subtitle = "مفيد إذا كانت البوابة تدير التوجيه بدلاً من رابط Ollama مباشر.",
                                checked = state.fallbackUseNewApiGateway,
                                onCheckedChange = viewModel::setFallbackUseNewApiGateway,
                                testTag = "ai_fallback_gateway_switch"
                            )

                            if (!state.fallbackUseNewApiGateway) {
                                OutlinedTextField(
                                    value = state.ollamaBaseUrl,
                                    onValueChange = viewModel::updateOllamaBaseUrl,
                                    label = { Text("رابط Ollama الخارجي الآمن (HTTPS)") },
                                    placeholder = { Text("https://ollama.yourdomain.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_ollama_url_input")
                                )

                                OutlinedTextField(
                                    value = state.ollamaApiKey,
                                    onValueChange = viewModel::updateOllamaApiKey,
                                    label = { Text("مفتاح حماية Ollama/النفق (اختياري)") },
                                    singleLine = true,
                                    visualTransformation = if (ollamaKeyVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { ollamaKeyVisible = !ollamaKeyVisible }) {
                                            Icon(
                                                imageVector = if (ollamaKeyVisible) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                                contentDescription = "إظهار/إخفاء مفتاح Ollama"
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_ollama_key_input")
                                )
                            }

                            OutlinedTextField(
                                value = state.ollamaModel,
                                onValueChange = viewModel::updateOllamaModel,
                                label = { Text("اسم النموذج الاحتياطي") },
                                placeholder = { Text(AiSettingsState.DEFAULT_QWEN_MODEL) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_ollama_model_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "حالة الاحتياطي: ${state.lastFallbackStatus}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = viewModel::testFallbackQwen,
                                    enabled = !state.isTestingFallback,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("ai_test_fallback_button")
                                ) {
                                    if (state.isTestingFallback) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("اختبار Qwen")
                                }
                            }

                            state.fallbackTestResult?.let { fbText ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "نتيجة اختبار الاحتياطي: $fbText",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Unified Style Controls: Tone, Length & Language
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "نبرة الرد والطول واللغة (Unified Smart Reply)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Text(
                                text = "نبرة الرد",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ResponseTone.entries.forEach { tone ->
                                    FilterChip(
                                        selected = state.responseTone == tone,
                                        onClick = { viewModel.selectResponseTone(tone) },
                                        label = { Text(tone.labelAr) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("ai_tone_chip_${tone.name}")
                                    )
                                }
                            }

                            if (state.responseTone == ResponseTone.CUSTOM) {
                                OutlinedTextField(
                                    value = state.customToneInstruction,
                                    onValueChange = viewModel::updateCustomToneInstruction,
                                    label = { Text("تعليمات النبرة المخصصة") },
                                    placeholder = { Text("مثال: رد بلهجة سعودية دافئة ومهذبة") },
                                    minLines = 2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_custom_tone_input")
                                )
                            }

                            Text(
                                text = "طول الرد المستهدف",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ResponseLength.entries.forEach { len ->
                                    FilterChip(
                                        selected = state.responseLength == len,
                                        onClick = { viewModel.selectResponseLength(len) },
                                        label = { Text("${len.labelAr} (~${len.defaultMaxChars})") },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("ai_length_chip_${len.name}")
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = if (state.maxReplyLengthChars > 0) state.maxReplyLengthChars.toString() else "",
                                onValueChange = viewModel::updateMaxReplyLengthChars,
                                label = { Text("الحد الأقصى لطول الرد بالحروف (40 - 2000)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_max_reply_chars_input")
                            )

                            Text(
                                text = "لغة الرد",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ReplyLanguage.entries.forEach { lang ->
                                    FilterChip(
                                        selected = state.replyLanguage == lang,
                                        onClick = { viewModel.selectReplyLanguage(lang) },
                                        label = { Text(lang.labelAr) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("ai_language_chip_${lang.name}")
                                    )
                                }
                            }

                            if (state.replyLanguage == ReplyLanguage.CUSTOM) {
                                OutlinedTextField(
                                    value = state.customReplyLanguage,
                                    onValueChange = viewModel::updateCustomReplyLanguage,
                                    label = { Text("اسم اللغة المطلوبة") },
                                    placeholder = { Text("مثال: الفرنسية أو الأردية") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_custom_language_input")
                                )
                            }
                        }
                    }
                }

                // 5. Smart Advisor Collaboration, Local Context Memory & Consent
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "الذاكرة وأدوات المنسّق (Smart Advisor + Webhook)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            SettingSwitchRow(
                                title = "تفعيل سياق المحادثة المتتابع",
                                subtitle = "يحتفظ بسياق الحوار وآخر منتج نوقش للرد الدقيق على الأسئلة اللاحقة.",
                                checked = state.useConversationContext,
                                onCheckedChange = viewModel::setUseConversationContext,
                                testTag = "ai_context_enabled_switch"
                            )

                            if (state.useConversationContext) {
                                Column {
                                    Text(
                                        text = "نافذة سياق المحادثة المحلية: ${state.contextWindowPairs} أزواج رسائل (${state.contextWindowPairs * 2} رسالة)",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Slider(
                                        value = state.contextWindowPairs.toFloat(),
                                        onValueChange = { viewModel.updateContextWindowPairs(it.roundToInt()) },
                                        valueRange = 6f..8f,
                                        steps = 1,
                                        modifier = Modifier.testTag("ai_context_window_slider")
                                    )
                                }
                            }

                            SettingSwitchRow(
                                title = "تفعيل قواعد الكلمات المفتاحية ضمن المسار الموحد",
                                subtitle = "تُفحص القواعد المخصصة قبل استدعاء الذكاء الاصطناعي، والقواعد غير الحاسمة تُمرر كسياق له.",
                                checked = state.useKeywordRules,
                                onCheckedChange = viewModel::setUseKeywordRules,
                                testTag = "ai_keyword_rules_switch"
                            )

                            SettingSwitchRow(
                                title = "تفعيل استشارة Smart Advisor لبيانات المتجر",
                                subtitle = "يُستعلم فقط عند الحاجة لأسعار المنتجات أو العروض أو سياسات الشحن والاسترجاع.",
                                checked = state.saCollaborationEnabled,
                                onCheckedChange = viewModel::setSaCollaborationEnabled,
                                testTag = "ai_sa_collab_switch"
                            )

                            SettingSwitchRow(
                                title = "السماح باستدعاء Webhook كأداة للمنسّق",
                                subtitle = "يسمح للذكاء الاصطناعي بالاستعلام من الأنظمة الخارجية (ERP/CRM) عبر Webhook في وضع AI_TOOL.",
                                checked = state.webhookToolEnabled,
                                onCheckedChange = viewModel::setWebhookToolEnabled,
                                testTag = "ai_webhook_tool_switch"
                            )

                            SettingSwitchRow(
                                title = "مزامنة ذاكرة العميل الدائمة مع المتجر",
                                subtitle = "يحفظ فقط الملخص الدائم والتفضيلات المؤكدة — ولا يرسل سجل الدردشة الخام أبداً.",
                                checked = state.syncCustomerMemory,
                                onCheckedChange = viewModel::setSyncCustomerMemory,
                                testTag = "ai_sync_memory_switch"
                            )

                            SettingSwitchRow(
                                title = "اشتراط موافقة العميل قبل الحفظ الدائم",
                                subtitle = "يمنع إرسال أي تحديث لذاكرة العميل الدائمة دون موافقة صريحة.",
                                checked = state.requireCustomerConsent,
                                onCheckedChange = viewModel::setRequireCustomerConsent,
                                testTag = "ai_require_consent_switch"
                            )

                            OutlinedTextField(
                                value = state.safeFallbackReply,
                                onValueChange = viewModel::updateSafeFallbackReply,
                                label = { Text("رد بديل آمن عند فشل جميع المحركات (اختياري)") },
                                placeholder = { Text("اتركه فارغاً لعدم إرسال رد غير موثوق") },
                                singleLine = false,
                                minLines = 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_safe_fallback_input")
                            )

                            OutlinedTextField(
                                value = state.systemPrompt,
                                onValueChange = viewModel::updateSystemPrompt,
                                label = { Text("التوجيه الأساسي (System Prompt)") },
                                minLines = 3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_system_prompt_input")
                            )

                            if (state.lastErrorRedacted.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "آخر خطأ منقّح: ${state.lastErrorRedacted}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp)
                                    )
                                }
                            }

                            Button(
                                onClick = viewModel::saveSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("ai_save_button")
                            ) {
                                Text("حفظ جميع الإعدادات")
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
