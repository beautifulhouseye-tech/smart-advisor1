package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.engine.ProductMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads, validates, caches, and permissionlessly stages HTTPS WooCommerce product images
 * inside internal app cache (FileProvider) and API 29+ MediaStore Pictures collection.
 *
 * Play-Policy compliant: requires ZERO storage/media manifest or runtime permissions.
 */
@Singleton
class ProductImageCacheManager internal constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val cacheRootDir: File? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ) : this(
        context = context,
        okHttpClient = okHttpClient,
        cacheRootDir = null,
        clockMs = { System.currentTimeMillis() }
    )

    sealed class CacheResult {
        data class Ready(
            val file: File,
            val fileProviderUri: Uri,
            val mediaStoreUri: Uri? = null,
            val mimeType: String,
            val width: Int,
            val height: Int,
            val byteSize: Long,
            val fromCache: Boolean
        ) : CacheResult()

        data class Rejected(
            val reason: String,
            val error: Throwable? = null
        ) : CacheResult()
    }

    suspend fun getOrDownload(
        imageUrl: String?,
        maxBytes: Long = MAX_IMAGE_BYTES
    ): CacheResult = withContext(Dispatchers.IO) {
        val cleanUrl = imageUrl?.trim().orEmpty()
        if (!ProductMessage.isValidProductImageUrl(cleanUrl)) {
            return@withContext CacheResult.Rejected("رابط الصورة غير صالح أو غير مشفر (HTTPS مطلوب)")
        }

        val dir = ensureCacheDir()
        cleanupExpiredLocked(dir)

        val cacheKey = sha256(cleanUrl)
        val cachedFile = findExistingCachedFile(dir, cacheKey)
        if (cachedFile != null && (clockMs() - cachedFile.lastModified()) <= CACHE_TTL_MS) {
            val validation = validateCachedImageFile(cachedFile, maxBytes)
            if (validation != null) {
                val providerUri = resolveFileProviderUri(cachedFile)
                return@withContext CacheResult.Ready(
                    file = cachedFile,
                    fileProviderUri = providerUri,
                    mediaStoreUri = null,
                    mimeType = validation.mimeType,
                    width = validation.width,
                    height = validation.height,
                    byteSize = cachedFile.length(),
                    fromCache = true
                )
            } else {
                cachedFile.delete()
            }
        }

        val request = runCatching {
            Request.Builder()
                .url(cleanUrl)
                .get()
                .header("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8")
                .build()
        }.getOrElse { err ->
            return@withContext CacheResult.Rejected("تعذر بناء طلب تحميل الصورة", err)
        }

        return@withContext runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use CacheResult.Rejected("فشل تنزيل الصورة (HTTP ${response.code})")
                }
                val body = response.body ?: return@use CacheResult.Rejected("استجابة الصورة فارغة")
                val headerType = response.header("Content-Type")?.lowercase().orEmpty()
                if (headerType.isNotEmpty() && !headerType.startsWith("image/") && !headerType.startsWith("application/octet-stream")) {
                    return@use CacheResult.Rejected("المحتوى المرجع ليس صورة صالحة ($headerType)")
                }
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) {
                    return@use CacheResult.Rejected("حجم الصورة يتجاوز الحد الأقصى (${maxBytes / (1024 * 1024)} MB)")
                }

                val tmpFile = File(dir, "$cacheKey.tmp")
                var totalRead = 0L
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                totalRead += read
                                if (totalRead > maxBytes) {
                                    throw IllegalStateException("تجاوز حجم الصورة الحد الأقصى المسموح")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    if (totalRead <= 0L) {
                        tmpFile.delete()
                        return@use CacheResult.Rejected("ملف الصورة فارغ")
                    }

                    val validation = validateCachedImageFile(tmpFile, maxBytes)
                    if (validation == null) {
                        tmpFile.delete()
                        return@use CacheResult.Rejected("ملف الصورة تالف أو صيغته غير مدعومة")
                    }

                    val ext = extensionForMime(validation.mimeType)
                    val targetFile = File(dir, "$cacheKey.$ext")
                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        tmpFile.delete()
                    }
                    targetFile.setLastModified(clockMs())

                    val providerUri = resolveFileProviderUri(targetFile)
                    CacheResult.Ready(
                        file = targetFile,
                        fileProviderUri = providerUri,
                        mediaStoreUri = null,
                        mimeType = validation.mimeType,
                        width = validation.width,
                        height = validation.height,
                        byteSize = targetFile.length(),
                        fromCache = false
                    )
                } catch (e: Throwable) {
                    tmpFile.delete()
                    CacheResult.Rejected(e.message ?: "فشل حفظ أو فحص الصورة", e)
                }
            }
        }.getOrElse { err ->
            CacheResult.Rejected(err.message ?: "تعذر تنزيل الصورة", err)
        }
    }

    /**
     * Stages the validated cache image into MediaStore (API 29+, zero permissions required)
     * so WhatsApp's in-app Gallery picker immediately shows it as the newest image.
     */
    suspend fun stageForGalleryPicker(ready: CacheResult.Ready): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext null
        }
        runCatching {
            val resolver = context.contentResolver
            val fileName = "sa_product_${clockMs()}.${extensionForMime(ready.mimeType)}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, ready.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AutoMateAI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_ADDED, clockMs() / 1000L)
                put(MediaStore.Images.Media.DATE_MODIFIED, clockMs() / 1000L)
            }
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            resolver.openOutputStream(inserted)?.use { out ->
                FileInputStream(ready.file).use { input ->
                    input.copyTo(out)
                }
            } ?: run {
                resolver.delete(inserted, null, null)
                return@withContext null
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(inserted, publishValues, null, null)
            inserted
        }.getOrNull()
    }

    suspend fun cleanupStagedMedia(mediaStoreUriString: String?) = withContext(Dispatchers.IO) {
        val raw = mediaStoreUriString?.trim().orEmpty()
        if (raw.isEmpty()) return@withContext
        runCatching {
            val uri = Uri.parse(raw)
            if (uri.scheme.equals("content", ignoreCase = true) &&
                uri.authority == MediaStore.AUTHORITY
            ) {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    fun cleanupExpiredCache() {
        val dir = ensureCacheDir()
        cleanupExpiredLocked(dir)
    }

    private fun ensureCacheDir(): File {
        val root = cacheRootDir ?: File(context.cacheDir, CACHE_SUBDIR)
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun cleanupExpiredLocked(dir: File) {
        val now = clockMs()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (now - file.lastModified() > CACHE_TTL_MS || file.name.endsWith(".tmp"))) {
                if (!file.name.endsWith(".tmp") || (now - file.lastModified() > 60_000L)) {
                    file.delete()
                }
            }
        }
    }

    private fun findExistingCachedFile(dir: File, cacheKey: String): File? {
        return dir.listFiles()?.firstOrNull { file ->
            file.isFile &&
                file.name.startsWith("$cacheKey.") &&
                !file.name.endsWith(".tmp")
        }
    }

    private data class ValidatedImage(
        val mimeType: String,
        val width: Int,
        val height: Int
    )

    private fun validateCachedImageFile(file: File, maxBytes: Long): ValidatedImage? {
        if (!file.exists() || !file.isFile) return null
        val len = file.length()
        if (len <= 12L || len > maxBytes) return null

        val header = ByteArray(16)
        val headerRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(-1)
        if (headerRead < 12) return null

        val magicMime = detectMagicMime(header, headerRead) ?: return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }

        val decodedMime = options.outMimeType?.lowercase()
        val width = options.outWidth
        val height = options.outHeight

        if (decodedMime != null && !decodedMime.startsWith("image/")) {
            return null
        }
        // When real BitmapFactory decodes bounds, require positive dimensions
        if (decodedMime != null && (width <= 0 || height <= 0)) {
            return null
        }

        return ValidatedImage(
            mimeType = decodedMime ?: magicMime,
            width = if (width > 0) width else 1,
            height = if (height > 0) height else 1
        )
    }

    private fun resolveFileProviderUri(file: File): Uri {
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            Uri.fromFile(file)
        }
    }

    companion object {
        const val CACHE_SUBDIR = "product_images"
        const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
        const val CACHE_TTL_MS = 24L * 60L * 60_000L

        internal fun detectMagicMime(bytes: ByteArray, length: Int): String? {
            if (length < 12) return null
            // JPEG: FF D8 FF
            if ((bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 &&
                (bytes[2].toInt() and 0xFF) == 0xFF
            ) {
                return "image/jpeg"
            }
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if ((bytes[0].toInt() and 0xFF) == 0x89 &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
            ) {
                return "image/png"
            }
            // WEBP: RIFF....WEBP
            if (bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return "image/webp"
            }
            // GIF: GIF87a / GIF89a
            if (bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte()
            ) {
                return "image/gif"
            }
            return null
        }

        private fun extensionForMime(mimeType: String): String {
            return when (mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
