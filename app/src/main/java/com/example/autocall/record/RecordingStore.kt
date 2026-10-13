package com.example.autocall.record

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File

/** 落盘请求：一次录音的全部事实。 */
data class StoreRequest(
    val seq: Int,
    val callId: String,
    val direction: CallDirection,
    val simSlot: Int,
    val caller: String?,
    val callee: String?,
    val contactName: String,
    val accountNumber: String?,
    val balance: String?,
    val startedAtMs: Long,
    val connectedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Long,
    val result: CallResult,
    val recordMode: String,
    val injectionFile: String?
)

/** 录音库列表项。 */
data class RecordingItem(
    val file: File,
    val fileName: String,
    val durationSec: Long,
    val result: String,
    val phoneNumber: String,
    val contactName: String,
    val startedAt: String,
    val publicUri: String?
)

/**
 * 录音落盘：
 *  1. 私有目录按 `年/月/日/` 分层（修 B15）；
 *  2. 同名 `.json` sidecar 元数据，使录音自描述；
 *  3. `MediaStore` 镜像到公共库 `Music/AUTOcall/yyyy-MM-dd/`，让系统文件管理器可见。
 */
object RecordingStore {

    private const val TAG = "RecordingStore"
    private const val KEEP_DIR = "call_records"
    private const val PUBLIC_ALBUM = "AUTOcall"
    private const val MIME = "audio/mp4"

    /** 把临时录音落盘为最终文件。返回 (私有路径, 公共 URI)。 */
    fun commit(context: Context, temp: File, req: StoreRequest): Pair<String?, String?> {
        val fileName = RecordingNaming.buildFileName(
            req.seq, req.direction, req.simSlot, req.caller, req.callee,
            req.startedAtMs, req.durationSec, req.result
        )
        val relDir = RecordingNaming.relativeDir(req.startedAtMs)
        val targetDir = File(File(context.getExternalFilesDir(null), KEEP_DIR), relDir)
            .apply { if (!exists()) mkdirs() }
        val target = File(targetDir, fileName)

        return try {
            if (temp.absolutePath != target.absolutePath) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            writeSidecar(context, target, fileName, req)
            val uri = mirrorToMediaStore(context, target, fileName, relDir)
            target.absolutePath to uri
        } catch (e: Exception) {
            Log.e(TAG, "commit failed: ${e.message}")
            null to null
        }
    }

    /** 供录音库使用：扫描私有目录下所有音频 + 其 sidecar。 */
    fun listAll(context: Context): List<RecordingItem> {
        val root = File(context.getExternalFilesDir(null), KEEP_DIR)
        if (!root.exists()) return emptyList()
        val items = mutableListOf<RecordingItem>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "m4a" || it.extension == "wav") }
            .forEach { f ->
                val meta = readSidecar(File(f.parentFile, f.nameWithoutExtension + ".json"))
                items.add(
                    RecordingItem(
                        file = f,
                        fileName = f.name,
                        durationSec = meta?.optLong("durationSec", 0L) ?: 0L,
                        result = meta?.optString("result", "unknown") ?: "unknown",
                        phoneNumber = meta?.optString("callee", "") ?: "",
                        contactName = meta?.optString("contactName", "") ?: "",
                        startedAt = meta?.optString("startedAt", "") ?: "",
                        publicUri = meta?.optString("mediaStoreUri", null).takeIf { !it.isNullOrEmpty() }
                    )
                )
            }
        return items.sortedByDescending { it.startedAt }
    }

    fun delete(context: Context, item: RecordingItem): Boolean {
        var ok = true
        runCatching {
            if (item.file.exists()) ok = item.file.delete()
            File(item.file.parentFile, item.file.nameWithoutExtension + ".json").let { if (it.exists()) it.delete() }
            item.publicUri?.let { uriStr ->
                runCatching {
                    context.contentResolver.delete(android.net.Uri.parse(uriStr), null, null)
                }
            }
        }.onFailure { ok = false }
        return ok
    }

    private fun writeSidecar(context: Context, audio: File, fileName: String, req: StoreRequest) {
        val json = JSONObject().apply {
            put("schema", 1)
            put("seq", req.seq)
            put("callId", req.callId)
            put("direction", req.direction.tag)
            put("simSlot", req.simSlot)
            put("caller", req.caller ?: "unknown")
            put("callee", req.callee ?: "unknown")
            put("contactName", req.contactName)
            put("accountNumber", req.accountNumber)
            put("balance", req.balance)
            put("startedAt", iso(req.startedAtMs))
            put("connectedAt", iso(req.connectedAtMs))
            put("endedAt", iso(req.endedAtMs))
            put("durationSec", req.durationSec)
            put("result", req.result.code)
            put("recordMode", req.recordMode)
            put("audio", JSONObject().apply {
                put("codec", "aac-lc")
                put("sampleRate", 16000)
                put("channels", 1)
                put("bitrate", 32000)
                put("container", audio.extension)
            })
            put("injection", JSONObject().apply {
                put("enabled", req.injectionFile != null)
                put("file", req.injectionFile)
            })
            put("fileName", fileName)
        }
        File(audio.parentFile, audio.nameWithoutExtension + ".json").writeText(json.toString(2))
    }

    /** 在 MediaStore 中查同名记录的 URI，便于列表项跳转公共库。 */
    fun findPublicUri(context: Context, fileName: String): String? = runCatching {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(fileName), null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                android.content.ContentUris.withAppendedId(collection, id).toString()
            } else null
        }
    }.getOrNull()

    private fun mirrorToMediaStore(context: Context, src: File, fileName: String, relDir: String): String? {
        return try {
            val publicRel = "${Environment.DIRECTORY_MUSIC}/$PUBLIC_ALBUM/${relDir.replace('/', '-')}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, publicRel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore mirror failed: ${e.message}")
            null
        }
    }

    private fun readSidecar(file: File): JSONObject? =
        runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull()

    private fun iso(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
            .format(java.util.Date(ms))
}
