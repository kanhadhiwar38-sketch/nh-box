package com.puja.user.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background download manager service for N𓆙G Series.
 *
 * Supports: start / pause / resume / cancel, live progress via LocalBroadcastManager,
 * persistence across app restarts (SharedPreferences), up to 3 concurrent downloads,
 * and filenames that preserve the ORIGINAL file extension found in the source URL.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START        = "com.puja.user.download.START"
        const val ACTION_PAUSE        = "com.puja.user.download.PAUSE"
        const val ACTION_RESUME       = "com.puja.user.download.RESUME"
        const val ACTION_CANCEL       = "com.puja.user.download.CANCEL"

        const val EXTRA_URL           = "extra_dl_url"
        const val EXTRA_TITLE         = "extra_dl_title"
        const val EXTRA_ID            = "extra_dl_id"

        const val BROADCAST_PROGRESS  = "com.puja.user.DOWNLOAD_PROGRESS"
        const val BROADCAST_DONE      = "com.puja.user.DOWNLOAD_DONE"
        const val BROADCAST_ERROR     = "com.puja.user.DOWNLOAD_ERROR"
        const val BROADCAST_CANCELLED = "com.puja.user.DOWNLOAD_CANCELLED"

        const val KEY_ID              = "id"
        const val KEY_TITLE           = "title"
        const val KEY_PCT             = "pct"
        const val KEY_BYTES           = "bytes"
        const val KEY_TOTAL           = "total"
        const val KEY_PATH            = "path"
        const val KEY_ERROR           = "error"

        const val PREFS_NAME          = "nhseries_downloads"
        const val PREFS_KEY_LIST      = "download_list"
        const val FOLDER_NAME         = "NHSeries"
        private const val CHANNEL_ID  = "nhseries_dl_channel"
        private const val NOTIF_BASE  = 8000
        private const val BUFFER_SIZE = 16384
    }

    private val executor   = Executors.newFixedThreadPool(3)
    private val lbm        get() = LocalBroadcastManager.getInstance(this)
    private val notifMgr   get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private val pausedFlags = ConcurrentHashMap<String, Boolean>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val activeCount = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url   = intent.getStringExtra(EXTRA_URL)   ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "file"
                val id    = intent.getStringExtra(EXTRA_ID)    ?: generateId(url)
                pausedFlags[id] = false
                cancelFlags[id] = false
                activeCount.incrementAndGet()
                startForeground(NOTIF_BASE, buildProgressNotif(title, id, 0, true))
                executor.execute { download(id, url, title) }
            }
            ACTION_PAUSE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                pausedFlags[id] = true
                updateRecord(id) { it.put("status", "paused") }
            }
            ACTION_RESUME -> {
                val id    = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val rec   = getRecord(id)                   ?: return START_NOT_STICKY
                val url   = rec.optString("url")
                val title = rec.optString("title")
                pausedFlags[id] = false
                cancelFlags[id] = false
                activeCount.incrementAndGet()
                startForeground(NOTIF_BASE, buildProgressNotif(title, id, 0, true))
                executor.execute { download(id, url, title) }
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                cancelFlags[id] = true
                pausedFlags[id] = false
            }
        }
        return START_NOT_STICKY
    }

    // ── Core download ────────────────────────────────────────────────────────
    private fun download(id: String, urlStr: String, title: String) {
        var connection: HttpURLConnection? = null
        var fos: FileOutputStream? = null

        try {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER_NAME
            ).also { if (!it.exists()) it.mkdirs() }

            val ext      = guessExt(urlStr)
            val safe     = sanitize(title)
            val priorRecord = getRecord(id)
            val existing = priorRecord?.optString("path", "")
            val priorTotal = priorRecord?.optLong("total", -1L) ?: -1L
            val file     = if (!existing.isNullOrBlank() && File(existing).exists())
                               File(existing)
                           else
                               uniqueFile(folder, safe, ext)

            // If a previous attempt already finished writing the full file (e.g. the
            // user hit Retry on a download that actually completed, or the app was
            // killed right after the file was written but before the record was
            // updated), skip the network call entirely and mark it done.
            if (file.exists() && priorTotal > 0 && file.length() >= priorTotal) {
                updateRecord(id) {
                    it.put("status", "done")
                    it.put("downloaded", file.length())
                    it.put("path", file.absolutePath)
                }
                broadcastDone(id, file.absolutePath)
                runCatching {
                    notifMgr.notify(NOTIF_BASE + id.hashCode(), buildDoneNotif(title, file))
                }
                stopIfIdle()
                return
            }

            val startByte = if (file.exists()) file.length() else 0L

            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout    = 30_000
                requestMethod  = "GET"
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                if (startByte > 0) setRequestProperty("Range", "bytes=$startByte-")
                connect()
            }

            var code = connection.responseCode

            // Server says the range we asked for doesn't exist — almost always because
            // the file we already have is already complete. Verify against Content-Range
            // / re-fetch without Range as a fallback rather than surfacing this as an error.
            if (code == 416) {
                val total416 = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                if (total416 > 0 && startByte >= total416) {
                    updateRecord(id) {
                        it.put("status", "done")
                        it.put("downloaded", startByte)
                        it.put("total", total416)
                        it.put("path", file.absolutePath)
                    }
                    broadcastDone(id, file.absolutePath)
                    runCatching {
                        notifMgr.notify(NOTIF_BASE + id.hashCode(), buildDoneNotif(title, file))
                    }
                    stopIfIdle()
                    return
                }
                // Range genuinely not satisfiable for an incomplete file — restart from scratch.
                connection.disconnect()
                file.delete()
                connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout    = 30_000
                    requestMethod  = "GET"
                    setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    connect()
                }
                code = connection.responseCode
            }

            if (code !in 200..299) {
                broadcastError(id, "Server error $code")
                stopIfIdle(); return
            }

            val startByteActual = if (file.exists()) file.length() else 0L
            val contentLen = connection.contentLengthLong
            val total      = if (startByteActual > 0 && code == 206) startByteActual + contentLen else contentLen
            var downloaded = startByteActual

            saveRecord(JSONObject().apply {
                put("id", id); put("url", urlStr); put("title", title)
                put("path", file.absolutePath); put("total", total)
                put("downloaded", downloaded); put("status", "downloading")
                put("ts", System.currentTimeMillis())
            })

            fos = FileOutputStream(file, startByteActual > 0)
            val buf      = ByteArray(BUFFER_SIZE)
            val input    = connection.inputStream
            var lastPct  = -1
            var lastNotif = System.currentTimeMillis()

            while (true) {
                while (pausedFlags[id] == true) {
                    if (cancelFlags[id] == true) break
                    Thread.sleep(200)
                }
                if (cancelFlags[id] == true) break

                val read = input.read(buf)
                if (read == -1) break
                fos.write(buf, 0, read)
                downloaded += read

                val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                val now = System.currentTimeMillis()

                if (pct != lastPct || now - lastNotif > 1000) {
                    lastPct   = pct
                    lastNotif = now
                    updateRecord(id) {
                        it.put("downloaded", downloaded)
                        it.put("status", "downloading")
                    }
                    broadcastProgress(id, pct, downloaded, total)
                    runCatching {
                        notifMgr.notify(NOTIF_BASE + id.hashCode(),
                            buildProgressNotif(title, id, if (pct < 0) 0 else pct, pct < 0))
                    }
                }
            }

            // Flush + close the OUTPUT file first — this is what actually matters.
            // Once this succeeds, the file on disk is complete and valid no matter
            // what happens afterwards (closing the network stream, scanning, etc).
            fos.flush()
            fos.close()
            runCatching { input.close() }

            if (cancelFlags[id] == true) {
                file.delete()
                updateRecord(id) { it.put("status", "cancelled") }
                broadcastCancelled(id)
                runCatching { notifMgr.cancel(NOTIF_BASE + id.hashCode()) }
            } else {
                // The file is fully written at this point — the download itself succeeded.
                // Mark it "done" FIRST, before touching anything else, so that an exception
                // thrown by a cosmetic follow-up action (media scan / notification) can
                // never downgrade a completed download back to "error".
                updateRecord(id) {
                    it.put("status", "done")
                    it.put("downloaded", downloaded)
                    it.put("path", file.absolutePath)
                }
                broadcastDone(id, file.absolutePath)

                runCatching {
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(file)))
                }
                runCatching {
                    notifMgr.notify(NOTIF_BASE + id.hashCode(), buildDoneNotif(title, file))
                }
            }

        } catch (e: Exception) {
            // Only reachable for genuine download failures (connection errors, disk
            // errors while writing, etc) — never for a download that already finished.
            val msg = when {
                e is java.net.SocketTimeoutException -> "Connection timed out"
                e is java.io.IOException && e.message?.contains("ENOSPC") == true -> "Not enough storage space"
                else -> e.message ?: e.javaClass.simpleName
            }
            updateRecord(id) { it.put("status", "error"); it.put("error", msg) }
            broadcastError(id, msg)
            runCatching { notifMgr.cancel(NOTIF_BASE + id.hashCode()) }
        } finally {
            fos?.runCatching { close() }
            connection?.disconnect()
            pausedFlags.remove(id)
            cancelFlags.remove(id)
            stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (activeCount.decrementAndGet() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Broadcasts ───────────────────────────────────────────────────────────
    private fun broadcastProgress(id: String, pct: Int, bytes: Long, total: Long) {
        lbm.sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            putExtra(KEY_ID, id); putExtra(KEY_PCT, pct)
            putExtra(KEY_BYTES, bytes); putExtra(KEY_TOTAL, total)
        })
    }
    private fun broadcastDone(id: String, path: String) {
        lbm.sendBroadcast(Intent(BROADCAST_DONE).apply {
            putExtra(KEY_ID, id); putExtra(KEY_PATH, path)
        })
    }
    private fun broadcastError(id: String, msg: String) {
        lbm.sendBroadcast(Intent(BROADCAST_ERROR).apply {
            putExtra(KEY_ID, id); putExtra(KEY_ERROR, msg)
        })
    }
    private fun broadcastCancelled(id: String) {
        lbm.sendBroadcast(Intent(BROADCAST_CANCELLED).apply { putExtra(KEY_ID, id) })
    }

    // ── SharedPreferences persistence ─────────────────────────────────────────
    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveRecord(obj: JSONObject) {
        val list = getList()
        val idx  = (0 until list.length()).firstOrNull {
            list.getJSONObject(it).optString("id") == obj.optString("id")
        }
        if (idx != null) list.put(idx, obj) else list.put(obj)
        prefs().edit().putString(PREFS_KEY_LIST, list.toString()).apply()
    }

    private fun updateRecord(id: String, block: (JSONObject) -> Unit) {
        val list = getList()
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            if (obj.optString("id") == id) { block(obj); list.put(i, obj); break }
        }
        prefs().edit().putString(PREFS_KEY_LIST, list.toString()).apply()
    }

    private fun getRecord(id: String): JSONObject? {
        val list = getList()
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            if (obj.optString("id") == id) return obj
        }
        return null
    }

    private fun getList(): JSONArray {
        val s = prefs().getString(PREFS_KEY_LIST, "[]") ?: "[]"
        return try { JSONArray(s) } catch (_: Exception) { JSONArray() }
    }

    // ── Notifications ─────────────────────────────────────────────────────────
    private fun buildProgressNotif(title: String, id: String, pct: Int, indet: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, pct, indet)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                pendingAction(id, ACTION_CANCEL))
            .build()

    private fun buildDoneNotif(title: String, file: File): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, file.hashCode(),
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(file), "video/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setSubText("Saved to Movies/$FOLDER_NAME")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun pendingAction(id: String, action: String): PendingIntent {
        val i = Intent(this, DownloadService::class.java).apply {
            this.action = action; putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getService(
            this, id.hashCode() + action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "$FOLDER_NAME Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Download progress"; setSound(null, null) }
            notifMgr.createNotificationChannel(ch)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Parses the total file size out of a "Content-Range: bytes X-Y/TOTAL" header.
     * Returns -1 if the header is missing, malformed, or the server doesn't know
     * the total size (TOTAL == "*").
     */
    private fun parseContentRangeTotal(headerValue: String?): Long {
        if (headerValue.isNullOrBlank()) return -1L
        val totalPart = headerValue.substringAfterLast("/", "").trim()
        return totalPart.toLongOrNull() ?: -1L
    }

    /**
     * Returns the file extension to use for the saved file, preserving the
     * ORIGINAL extension found in the source URL whenever one is present
     * (e.g. ".mp4", ".mkv", ".m3u8", ".pdf", etc). Falls back to "mp4" only
     * when the URL has no usable extension at all (e.g. a bare API endpoint).
     */
    private fun guessExt(url: String): String {
        try {
            val pathOnly = url.substringBefore("?").substringBefore("#")
            val lastSeg  = pathOnly.substringAfterLast("/")
            val decoded  = try { URLDecoder.decode(lastSeg, "UTF-8") } catch (_: Exception) { lastSeg }
            if (decoded.contains(".")) {
                val candidate = decoded.substringAfterLast(".").lowercase().trim()
                // Must look like a real extension: short, alphanumeric only.
                if (candidate.isNotEmpty() && candidate.length in 1..6 && candidate.all { it.isLetterOrDigit() }) {
                    return candidate
                }
            }
        } catch (_: Exception) {
            // fall through to default
        }
        return "mp4"
    }

    private fun sanitize(s: String) =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifEmpty { "file" }

    private fun uniqueFile(folder: File, name: String, ext: String): File {
        var f = File(folder, "$name.$ext"); var n = 1
        while (f.exists()) { f = File(folder, "${name}_$n.$ext"); n++ }
        return f
    }

    private fun generateId(url: String) =
        (url + System.currentTimeMillis()).hashCode().toString(16)

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
