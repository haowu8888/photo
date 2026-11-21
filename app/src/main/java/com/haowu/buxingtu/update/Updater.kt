package com.haowu.buxingtu.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.haowu.buxingtu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 自动更新：Flow 流水线 shuitu 每次把新 APK 传到 Cloudreve 的 /builds/shuitu（只留最新一版），
 * 文件名带版本（Shuitu-0.3.<运行号>.apk）。这里按分享里的文件名判断有没有新版本，下载后交给系统安装。
 *
 * 不另做签名校验：安卓本身只允许同一签名的 APK 覆盖安装，下载被篡改的包装不上；
 * 安装前也先比对一次签名，给出明确提示。
 */
object Updater {
    private const val SERVER = "https://cloudreve.haowu.fun"
    private val apkName = Regex("""(?i)^shuitu-(\d+(?:\.\d+)*)\.apk$""")

    /** 分享 ID 由流水线构建时注入；本地构建为空，不检查更新 */
    val enabled: Boolean get() = BuildConfig.UPDATE_SHARE.isNotEmpty()

    data class Release(val version: String, val name: String, val size: Long, val path: String)

    /** @return 网盘里比当前新的版本，没有返回 null */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val uri = URLEncoder.encode("cloudreve://${BuildConfig.UPDATE_SHARE}@share/", "UTF-8")
        val data = api("GET", "/api/v4/file?uri=$uri&page=0&page_size=100", null)
        val files = data.optJSONArray("files") ?: JSONArray()
        var best: Release? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val m = apkName.find(f.optString("name")) ?: continue
            if (f.optInt("type") != 0) continue
            val r = Release(m.groupValues[1], f.getString("name"), f.optLong("size"), f.getString("path"))
            if (best == null || compareVersions(r.version, best.version) > 0) best = r
        }
        best?.takeIf { compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 }
    }

    /** 下载到缓存目录，onProgress(已下载, 总大小) 在下载线程回调。 */
    suspend fun download(context: Context, release: Release, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("uris", JSONArray().put(release.path)).put("download", true)
            val urls = api("POST", "/api/v4/file/url", body).optJSONArray("urls")
            var link = urls?.optJSONObject(0)?.optString("url").orEmpty()
            if (link.isEmpty()) error("网盘没有给出下载地址")
            if (link.startsWith("/")) link = SERVER + link
            // 网盘给的下载地址是 http 的（服务器再 301 到 https）：安卓默认禁止明文 HTTP，
            // HttpURLConnection 也不跟随 http→https 的跳转，直接换成 https
            if (link.startsWith("http://cloudreve.haowu.fun/")) link = "https://" + link.removePrefix("http://")

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // 只留这一个
            val file = File(dir, release.name)
            val conn = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) error("下载失败：HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            verify(context, file)
            file
        }

    /** 安装前检查：包名一致、版本更新、签名和已装的一样。 */
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("下载的安装包损坏了")
        val self = pm.getPackageInfo(context.packageName, flags)
        if (apk.packageName != context.packageName) error("安装包不是睡图")
        if (versionCode(apk) <= versionCode(self)) error("安装包版本不比当前新")
        if (signers(apk) != signers(self)) error("安装包签名和当前版本不一致，已停止更新")
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.signingInfo?.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** 是否已允许本应用安装未知来源应用（Android 8+ 要用户在设置里单独打开）。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Cloudreve v4 匿名 API（分享是公开的）。 */
    private fun api(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = (URL(SERVER + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: error("网盘返回 HTTP ${conn.responseCode}")
            val res = runCatching { JSONObject(text) }.getOrElse { error("网盘返回 HTTP ${conn.responseCode}") }
            if (res.optInt("code") != 0) error("网盘：${res.optString("msg")}（${res.optInt("code")}）")
            return res.optJSONObject("data") ?: JSONObject()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连不上网盘：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** 点分数字比较：0.3.10 比 0.3.9 新。 */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)?.toIntOrNull() ?: 0
            val q = y.getOrNull(i)?.toIntOrNull() ?: 0
            if (p != q) return p.compareTo(q)
        }
        return 0
    }
}
