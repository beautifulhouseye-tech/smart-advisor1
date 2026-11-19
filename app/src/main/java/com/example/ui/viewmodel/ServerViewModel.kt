package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.FieldType
import com.example.data.local.entity.GroupReplyMode
import com.example.data.local.entity.HeaderItem
import com.example.data.local.entity.RequestField
import com.example.data.local.entity.ServerConfigEntity
import com.example.data.local.entity.WebhookMode
import com.example.data.repository.ServerConfigRepository
import com.example.engine.IncomingMessage
import com.example.engine.VariableEngine
import com.example.network.HttpTransport
import com.example.network.WebhookClient
import com.example.network.security.SecretVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val repo: ServerConfigRepository,
    private val webhookClient: WebhookClient,
    private val vault: SecretVault
) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _config = MutableStateFlow<ServerConfigEntity?>(null)
    val config: StateFlow<ServerConfigEntity?> = _config.asStateFlow()

    private val _previewJson = MutableStateFlow("")
    val previewJson: StateFlow<String> = _previewJson.asStateFlow()

    data class TestResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?,
        val durationMs: Long,
        val isToolPreview: Boolean = false
    )

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repo.get() ?: createDefaultConfig()
            _config.value = loaded
            updatePreview(loaded)
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        updateConfig { it.copy(isEnabled = enabled) }
    }

    fun updateUrl(url: String) {
        updateConfig { it.copy(url = url) }
    }

    fun updateMethod(method: String) {
        updateConfig { it.copy(method = method) }
    }

    fun updateContentType(type: String) {
        updateConfig { it.copy(contentType = type) }
    }

    fun updateReplyKey(key: String) {
        updateConfig { it.copy(responseReplyKey = key) }
    }

    fun updateTimeout(seconds: Int) {
        updateConfig {
            it.copy(
                timeoutSeconds = seconds.coerceIn(
                    HttpTransport.MIN_TIMEOUT_SECONDS,
                    HttpTransport.MAX_TIMEOUT_SECONDS
                )
            )
        }
    }

    fun updateRetry(count: Int) {
        updateConfig { it.copy(retryCount = count.coerceIn(1, HttpTransport.MAX_ALLOWED_ATTEMPTS)) }
    }

    fun updateGroupMode(mode: GroupReplyMode) {
        updateConfig { it.copy(sendOnGroup = mode.name) }
    }

    fun updatePrivate(enabled: Boolean) {
        updateConfig { it.copy(sendOnPrivate = enabled) }
    }

    fun updateWebhookMode(mode: WebhookMode) {
        updateConfig { it.copy(webhookMode = mode.name) }
    }

    fun updateToolDescription(description: String) {
        updateConfig { it.copy(toolDescription = description) }
    }

    // Headers
    fun addHeader() {
        updateHeadersList { list ->
            list + HeaderItem(key = "X-New", valueRef = "", isSecret = false)
        }
    }

    fun removeHeader(index: Int) {
        updateHeadersList { list ->
            if (index !in list.indices) return@updateHeadersList list
            val target = list[index]
            if (target.isSecret && target.valueRef.isNotBlank()) {
                vault.remove(target.valueRef)
            }
            list.toMutableList().apply { removeAt(index) }
        }
    }

    fun updateHeader(index: Int, key: String, valueRef: String, isSecret: Boolean) {
        updateHeadersList { list ->
            if (index !in list.indices) return@updateHeadersList list
            val existing = list[index]
            val resolvedRef = if (isSecret) {
                if (existing.isSecret && valueRef.isNotBlank()) {
                    valueRef
                } else if (existing.isSecret && existing.valueRef.isNotBlank()) {
                    existing.valueRef
                } else {
                    valueRef.ifBlank { generateSecretRef() }
                }
            } else {
                if (existing.isSecret && existing.valueRef.isNotBlank()) {
                    vault.remove(existing.valueRef)
                }
                valueRef
            }
            list.toMutableList().apply {
                set(
                    index,
                    HeaderItem(
                        key = key,
                        valueRef = resolvedRef,
                        isSecret = isSecret
                    )
                )
            }
        }
    }

    fun setHeaderSecretValue(index: Int, secretValue: String) {
        val currentList = decodeHeaders(_config.value?.headersJson.orEmpty())
        if (index !in currentList.indices) return
        val item = currentList[index]
        val ref = item.valueRef.ifBlank { generateSecretRef() }
        if (ref != item.valueRef || !item.isSecret) {
            updateHeadersList { list ->
                if (index !in list.indices) return@updateHeadersList list
                list.toMutableList().apply {
                    set(index, item.copy(valueRef = ref, isSecret = true))
                }
            }
        }
        if (secretValue.isEmpty()) {
            vault.remove(ref)
        } else {
            vault.put(ref, secretValue)
        }
    }

    fun hasStoredSecret(valueRef: String): Boolean {
        if (valueRef.isBlank()) return false
        return vault.get(valueRef).isNotEmpty()
    }

    fun decodeHeaders(raw: String): List<HeaderItem> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<HeaderItem>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Request fields
    fun addField() {
        updateFieldsList { list ->
            list + RequestField(
                key = "field_${list.size + 1}",
                valueTemplate = "{{message}}",
                type = FieldType.STRING,
                enabled = true,
                description = ""
            )
        }
    }

    fun removeField(index: Int) {
        updateFieldsList { list ->
            if (index !in list.indices) return@updateFieldsList list
            list.toMutableList().apply { removeAt(index) }
        }
    }

    fun updateField(
        index: Int,
        key: String,
        template: String,
        type: FieldType,
        description: String
    ) {
        updateFieldsList { list ->
            if (index !in list.indices) return@updateFieldsList list
            val existing = list[index]
            list.toMutableList().apply {
                set(
                    index,
                    existing.copy(
                        key = key,
                        valueTemplate = template,
                        type = type,
                        description = description
                    )
                )
            }
        }
    }

    fun toggleField(index: Int, enabled: Boolean) {
        updateFieldsList { list ->
            if (index !in list.indices) return@updateFieldsList list
            val existing = list[index]
            list.toMutableList().apply {
                set(index, existing.copy(enabled = enabled))
            }
        }
    }

    fun moveFieldUp(index: Int) {
        updateFieldsList { list ->
            if (index <= 0 || index >= list.size) return@updateFieldsList list
            list.toMutableList().apply {
                val item = removeAt(index)
                add(index - 1, item)
            }
        }
    }

    fun moveFieldDown(index: Int) {
        updateFieldsList { list ->
            if (index < 0 || index >= list.size - 1) return@updateFieldsList list
            list.toMutableList().apply {
                val item = removeAt(index)
                add(index + 1, item)
            }
        }
    }

    fun decodeFields(raw: String): List<RequestField> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<RequestField>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save() {
        val current = _config.value ?: return
        viewModelScope.launch {
            repo.save(current)
            _saveStatus.value = "تم حفظ إعدادات الخادم بنجاح"
        }
    }

    fun consumeSaveStatus() {
        _saveStatus.value = null
    }

    fun loadDefaults() {
        val wasEnabled = _config.value?.isEnabled ?: false
        val defaults = createDefaultConfig().copy(isEnabled = wasEnabled)
        _config.value = defaults
        updatePreview(defaults)
    }

    fun testRequest() {
        val current = _config.value ?: return
        if (_isTesting.value) return
        _isTesting.value = true
        _testResult.value = null

        viewModelScope.launch {
            val sampleMsg = sampleIncomingMessage()
            val result = webhookClient.send(current, sampleMsg)
            _testResult.value = when (result) {
                is HttpTransport.Result.Success -> TestResult(
                    success = true,
                    statusCode = result.code,
                    body = vault.redact(result.body),
                    error = null,
                    durationMs = result.ms,
                    isToolPreview = false
                )
                is HttpTransport.Result.Conflict -> TestResult(
                    success = false,
                    statusCode = result.code,
                    body = vault.redact(result.body),
                    error = "تعارض من الخادم (HTTP 409)",
                    durationMs = result.ms,
                    isToolPreview = false
                )
                is HttpTransport.Result.Failure -> TestResult(
                    success = false,
                    statusCode = result.code,
                    body = "",
                    error = vault.redact(result.error),
                    durationMs = result.ms,
                    isToolPreview = false
                )
            }
            _isTesting.value = false
        }
    }

    fun testAsAiTool(aiQuery: String = "الاستعلام عن حالة الطلب #1001") {
        val current = _config.value ?: return
        if (_isTesting.value) return
        _isTesting.value = true
        _testResult.value = null

        viewModelScope.launch {
            val sampleMsg = sampleIncomingMessage().copy(aiQuery = aiQuery)
            val toolRes = webhookClient.invokeAsAiTool(
                config = current,
                msg = sampleMsg,
                aiQuery = aiQuery,
                reason = "اختبار نتيجة أداة AI"
            )
            val displayText = buildString {
                append("المصدر: ${toolRes.sourceLabel} (درجة الثقة: خارجي غير متحقق منه)\n")
                if (toolRes.extractedText.isNotBlank()) {
                    append("النص المستخرج للـ AI:\n").append(toolRes.extractedText)
                } else if (toolRes.rawExcerpt.isNotBlank()) {
                    append("مقتطف الاستجابة المنقّح:\n").append(toolRes.rawExcerpt)
                }
                if (toolRes.truncated) {
                    append("\n[تم اقتطاع الاستجابة للحد الأقصى المسموح]")
                }
            }.trim()

            _testResult.value = TestResult(
                success = toolRes.ok,
                statusCode = toolRes.statusCode,
                body = displayText,
                error = toolRes.redactedError,
                durationMs = toolRes.latencyMs,
                isToolPreview = true
            )
            _isTesting.value = false
        }
    }

    private fun updateConfig(transform: (ServerConfigEntity) -> ServerConfigEntity) {
        val current = _config.value ?: createDefaultConfig()
        val updated = transform(current)
        _config.value = updated
        updatePreview(updated)
    }

    private fun updateHeadersList(transform: (List<HeaderItem>) -> List<HeaderItem>) {
        updateConfig { cfg ->
            val current = decodeHeaders(cfg.headersJson)
            val updated = transform(current)
            cfg.copy(headersJson = json.encodeToString(updated))
        }
    }

    private fun updateFieldsList(transform: (List<RequestField>) -> List<RequestField>) {
        updateConfig { cfg ->
            val current = decodeFields(cfg.requestFieldsJson)
            val updated = transform(current)
            cfg.copy(requestFieldsJson = json.encodeToString(updated))
        }
    }

    private fun updatePreview(config: ServerConfigEntity) {
        val sample = sampleIncomingMessage()
        val rawBody = if (config.effectiveWebhookMode() == WebhookMode.AI_TOOL) {
            webhookClient.buildAiToolBody(config, sample, sample.message, "معاينة أداة AI")
        } else {
            VariableEngine.buildRequestBody(config, sample)
        }
        _previewJson.value = if (config.contentType.equals("JSON", ignoreCase = true)) {
            try {
                val parsed = prettyJson.parseToJsonElement(rawBody)
                prettyJson.encodeToString(parsed)
            } catch (_: Exception) {
                rawBody
            }
        } else {
            rawBody
        }
    }

    private fun createDefaultConfig(): ServerConfigEntity {
        val defaultHeaders = listOf(
            HeaderItem(
                key = "X-Client",
                valueRef = "AutoMate-AI",
                isSecret = false
            )
        )
        val defaultFields = listOf(
            RequestField(
                key = "sender",
                valueTemplate = "{{sender}}",
                type = FieldType.STRING,
                enabled = true,
                description = "اسم أو رقم المرسل"
            ),
            RequestField(
                key = "message",
                valueTemplate = "{{message}}",
                type = FieldType.STRING,
                enabled = true,
                description = "نص الرسالة الواردة"
            ),
            RequestField(
                key = "app",
                valueTemplate = "{{app}}",
                type = FieldType.STRING,
                enabled = true,
                description = "حزمة تطبيق المراسلة"
            ),
            RequestField(
                key = "is_group",
                valueTemplate = "{{is_group}}",
                type = FieldType.BOOLEAN,
                enabled = true,
                description = "هل الرسالة ضمن مجموعة"
            )
        )
        return ServerConfigEntity(
            id = 1,
            isEnabled = false,
            url = "https://example.com/webhook",
            method = "POST",
            contentType = "JSON",
            headersJson = json.encodeToString(defaultHeaders),
            requestFieldsJson = json.encodeToString(defaultFields),
            responseReplyKey = "reply",
            timeoutSeconds = HttpTransport.DEFAULT_TIMEOUT_SECONDS,
            retryCount = HttpTransport.DEFAULT_MAX_ATTEMPTS,
            sendOnGroup = GroupReplyMode.KEYWORD_ONLY.name,
            sendOnPrivate = true,
            webhookMode = WebhookMode.AI_TOOL.name,
            toolDescription = ServerConfigEntity.DEFAULT_TOOL_DESCRIPTION,
            toolMaxResponseChars = ServerConfigEntity.DEFAULT_TOOL_MAX_CHARS
        )
    }

    private fun generateSecretRef(): String =
        "webhook_secret_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun sampleIncomingMessage(): IncomingMessage = IncomingMessage(
        app = "com.whatsapp",
        sender = "أحمد محمد",
        senderName = "أحمد محمد",
        message = "السلام عليكم، هل يتوفر لديكم التوصيل اليوم؟",
        phone = "+966501234567",
        groupName = "عملاء المتجر",
        isGroup = false,
        notificationId = "sample-notif-101",
        timestamp = 1_700_000_000_000L
    )
}
