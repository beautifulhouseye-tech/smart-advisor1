package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.security.SmartAdvisorPrefs
import com.example.ui.components.UnifiedTestResultCard
import com.example.ui.viewmodel.SaSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SaSettingsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiKeyRef by viewModel.apiKey.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(saveStatus) {
        saveStatus?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSaveStatus()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Smart Advisor") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("sa_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sa_save_button")
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
                // Enable / Disable SmartAdvisor Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SmartToy,
                                    contentDescription = "Smart Advisor",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = "تفعيل Smart Advisor",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "مصدر بيانات متجرك الموثوقة (WooCommerce: المنتجات، الأسعار، الطلبات، السياسات) كأداة للمنسّق الذكي.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = enabled,
                                onCheckedChange = viewModel::toggleEnabled,
                                modifier = Modifier.testTag("sa_enabled_switch")
                            )
                        }
                    }
                }

                // Credentials Card (Base URL + API Key)
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
                            Text(
                                text = "إعدادات الاتصال",
                                style = MaterialTheme.typography.titleMedium
                            )

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = viewModel::updateBaseUrl,
                                    label = { Text("رابط نقطة النهاية (Endpoint URL)") },
                                    placeholder = { Text(SmartAdvisorPrefs.DEFAULT_BASE_URL) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("sa_base_url_input")
                                )
                            }

                            OutlinedButton(
                                onClick = viewModel::restoreDefaultUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("sa_restore_default_url_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "استعادة الرابط الافتراضي (777736225.com)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            val hasKey = apiKeyRef.isNotBlank() && viewModel.hasStoredApiKey()
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { newValue ->
                                        apiKeyInput = newValue
                                        if (newValue.trim().isNotEmpty()) {
                                            viewModel.updateApiKey(newValue)
                                        } else if (!hasKey) {
                                            viewModel.updateApiKey("")
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (hasKey) "مفتاح API (محفوظ مشفراً — اكتب للتحديث)"
                                            else "مفتاح API (X-SA-API-Key)"
                                        )
                                    },
                                    placeholder = { Text("أدخل مفتاح Smart Advisor السري") },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Key,
                                            contentDescription = "مفتاح API",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    visualTransformation = if (showApiKey) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { showApiKey = !showApiKey },
                                            modifier = Modifier.testTag("sa_toggle_key_visibility")
                                        ) {
                                            Icon(
                                                imageVector = if (showApiKey) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                                contentDescription = "إظهار أو إخفاء المفتاح"
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("sa_api_key_input")
                                )
                            }

                            if (hasKey) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "مفتاح API محفوظ ومشفّر في SecretVault ($apiKeyRef)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            apiKeyInput = ""
                                            viewModel.clearApiKey()
                                        },
                                        modifier = Modifier.testTag("sa_clear_key_button")
                                    ) {
                                        Text("مسح المفتاح")
                                    }
                                }
                            } else {
                                Text(
                                    text = "تنبيه: يتطلب المسار مفتاح API عبر ترويسة X-SA-API-Key. أدخل المفتاح قبل الاختبار.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("sa_missing_key_hint")
                                )
                            }
                        }
                    }
                }

                // Connection Test Button + TestResultCard
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = viewModel::testConnection,
                            enabled = !isTesting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("sa_test_connection_button")
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("جارٍ اختبار الاتصال...")
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "اختبار الاتصال",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("اختبار الاتصال")
                            }
                        }

                        AnimatedVisibility(visible = testResult != null) {
                            testResult?.let { result ->
                                UnifiedTestResultCard(
                                    model = result.toUnifiedCardModel(),
                                    cardTestTag = "sa_test_result_card"
                                )
                            }
                        }
                    }
                }

                // How SmartAdvisor Works Card
                item {
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
                                    contentDescription = "كيف يعمل Smart Advisor",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "كيف يعمل Smart Advisor؟",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = "1. أدخل رابط متجرك (يتم تلقائياً استخدام مسار /wp-json/smart-advisor/v1/whatsapp/reply).\n" +
                                    "2. أدخل مفتاح API ليتم تشفيره وحفظه في SecretVault وإرساله في ترويسة X-SA-API-Key.\n" +
                                    "3. عند تفعيل الميزة، يرسل التطبيق بيانات الرسالة (app, sender, message, group_name, phone) ويعيد رد المستشار الذكي فوراً.\n" +
                                    "4. اضغط \"اختبار الاتصال\" لإرسال رسالة تجريبية والتحقق من استجابة الخادم.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}


