package com.dsh.android.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.dsh.android.BuildConfig
import com.dsh.android.EngineManager
import com.dsh.android.R
import com.dsh.android.log.DshLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * v1.3.0 in-app updates: the DSH harness from npm ([HarnessUpdater]) and the APK from this fork's GitHub releases
 * ([AppUpdater]). Checked on launch at most daily and from Settings > About & updates; each update is one tap.
 * [json] is the state the About section shows (through `AppState`).
 */
object UpdateCenter {
    private const val PREFS = "dsh_update"
    private const val TAG = "DSH"
    private const val HARNESS_TIMEOUT_MS = 20L * 60 * 1000

    /** System WebView major version (0 unknown); set by MainActivity. An updated harness needs >= 94 on the system flavor. */
    @Volatile var webViewMajor = 0

    @Volatile private var checking = false
    @Volatile private var harnessStatus = "idle"
    @Volatile private var harnessMessage = ""
    @Volatile private var appStatus = "idle"
    @Volatile private var appMessage = ""
    @Volatile private var appProgress = -1
    @Volatile private var release: AppUpdater.Release? = null
    @Volatile private var downloadedApk: AppUpdater.DownloadedApk? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun slots(c: Context) = EngineSlots(c.applicationContext.filesDir)

    /** Raw harness worker state, shared with [com.dsh.android.EngineManager] so a manual restore never interrupts an install. */
    val harnessWorkerStatus: String get() = harnessStatus

    fun json(context: Context): JSONObject {
        val p = prefs(context)
        val slots = slots(context)
        val current = slots.installedVersion()
        val previous = slots.previousVersion()
        val latest = p.getString("harnessLatest", null)
        val offer = HarnessUpdater(slots).offer(current, latest)
        val harnessActions = UpdateActions.harness(offer != null, harnessStatus, harnessBlocked() != null)
        val harness = JSONObject().put("current", current ?: JSONObject.NULL).put("latest", latest ?: JSONObject.NULL)
            .put("previous", previous ?: JSONObject.NULL)
            .put("canRollback", UpdateActions.rollback(previous != null && previous != current, harnessStatus))
            .put("status", when {
                harnessStatus != "idle" -> harnessStatus
                harnessBlocked() != null && offer != null -> "blocked"
                offer != null -> "available"
                latest != null -> "current"
                else -> "unknown"
            })
            .put("message", harnessMessage.ifEmpty { if (offer != null) harnessBlocked().orEmpty() else "" })
            .put("canUpdate", harnessActions.canUpdate).put("canInstall", false)
        val appLatest = release?.version ?: p.getString("appLatest", null)
        val appActions = UpdateActions.app(
            appLatest != null && Versions.isNewer(appLatest, BuildConfig.VERSION_NAME), appStatus,
            release?.let { downloadedApk?.isFor(it) } == true,
        )
        val appNotes = release?.notes?.let(AppUpdater::displayNotes).orEmpty()
        val appPage = release?.page?.takeIf(AppUpdater::isTrustedReleasePage)
        val app = JSONObject().put("current", BuildConfig.VERSION_NAME).put("latest", appLatest ?: JSONObject.NULL)
            .put("notes", appNotes).put("page", appPage ?: JSONObject.NULL)
            .put("status", when {
                appStatus != "idle" -> appStatus
                appLatest != null && Versions.isNewer(appLatest, BuildConfig.VERSION_NAME) -> "available"
                appLatest != null -> "current"
                else -> "unknown"
            })
            .put("message", appMessage).put("progress", appProgress)
            .put("canUpdate", appActions.canUpdate).put("canInstall", appActions.canInstall)
        return JSONObject().put("harness", harness).put("app", app)
            .put("checking", checking).put("lastCheck", p.getLong("lastCheck", 0L))
    }

    /** The checked app release's page, but only when it is this fork's HTTPS GitHub release URL. */
    fun releasePage(): String? = release?.page?.takeIf(AppUpdater::isTrustedReleasePage)

    /** Why the harness cannot be updated on this phone, or null. */
    private fun harnessBlocked(): String? =
        if (!BuildConfig.BUILT_IN_WEB_ENGINE && webViewMajor in 1..93)
            "Update Android System WebView (Chrome 94 or newer) first, or use the built-in-engine (gecko) build."
        else null

    /** On launch: at most daily, then a one-tap dialog for whatever is newer and not dismissed. */
    fun onUiReady(activity: Activity) {
        val p = prefs(activity)
        if (!HarnessUpdater.shouldCheck(System.currentTimeMillis(), p.getLong("lastCheck", 0L))) return
        check(activity) { offerDialog(activity) }
    }

    /** Asks npm and GitHub (background); [then] runs on the main thread afterwards. */
    fun check(activity: Activity, then: (() -> Unit)? = null) {
        if (checking) return
        checking = true
        val app = activity.applicationContext
        thread(name = "update-check") {
            val harness = runCatching { HarnessUpdater.latestFromRegistry(httpGet(HarnessUpdater.REGISTRY_URL)) }.getOrNull()
            val rel = runCatching { AppUpdater.parseRelease(httpGet(AppUpdater.RELEASES_URL), BuildConfig.BUILT_IN_WEB_ENGINE) }.getOrNull()
            prefs(app).edit().apply {
                putLong("lastCheck", System.currentTimeMillis())
                harness?.let { putString("harnessLatest", it) }
                rel?.let { putString("appLatest", it.version) }
            }.apply()
            if (rel != null) release = rel
            if (harnessStatus == "error") { harnessStatus = "idle"; harnessMessage = "" }
            if (appStatus == "error") { appStatus = "idle"; appMessage = "" }
            checking = false
            DshLog.i(TAG, "update check: harness latest=$harness, app latest=${rel?.version}")
            then?.let { activity.runOnUiThread(it) }
        }
    }

    private fun offerDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = prefs(activity)
        val state = json(activity)
        val h = state.getJSONObject("harness")
        val a = state.getJSONObject("app")
        val lines = mutableListOf<String>()
        val harnessNew = h.optString("status") == "available" && h.optString("latest") != p.getString("dismissedHarness", null)
        val appNew = a.optString("status") == "available" && a.optString("latest") != p.getString("dismissedApp", null)
        if (harnessNew) lines += "DSH harness ${h.optString("latest")} (you have ${h.optString("current")})"
        if (appNew) lines += "DSH for Android ${a.optString("latest")} (you have ${a.optString("current")})"
        if (lines.isEmpty()) return
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton(R.string.update_later) { _, _ ->
                p.edit().apply {
                    if (harnessNew) putString("dismissedHarness", h.optString("latest"))
                    if (appNew) putString("dismissedApp", a.optString("latest"))
                }.apply()
            }
        if (appNew) builder.setPositiveButton("Update app") { _, _ -> updateApp(activity) }
        if (harnessNew) {
            if (appNew) builder.setNeutralButton("Update harness") { _, _ -> updateHarness(activity) }
            else builder.setPositiveButton("Update") { _, _ -> updateHarness(activity) }
        }
        builder.show()
    }

    /** Installs the newer harness with the bundled pnpm, promotes it and restarts the engine (rollback if it fails to boot). */
    fun updateHarness(activity: Activity) {
        if (harnessStatus == "installing") return
        val app = activity.applicationContext
        val engine = EngineManager.get(app)
        val slots = slots(app)
        val target = HarnessUpdater(slots).offer(slots.installedVersion(), prefs(app).getString("harnessLatest", null))
        harnessBlocked()?.let { harnessStatus = "error"; harnessMessage = it; return }
        if (target == null) { harnessStatus = "idle"; harnessMessage = ""; return }
        val prefix = engine.prefixPath
        if (prefix == null) { harnessStatus = "error"; harnessMessage = "Start the engine once, then try again."; return }
        harnessStatus = "installing"
        harnessMessage = "Installing @deepseek-ai/dsh $target…"
        thread(name = "harness-update") {
            val updater = HarnessUpdater(slots)
            val log = StringBuilder()
            try {
                updater.prepareNext(target)
                val cmd = listOf("/system/bin/sh", "$prefix/bin/pnpm") + HarnessUpdater.pnpmArgs()
                val exit = engine.runWithEngineEnv(cmd, slots.next, HARNESS_TIMEOUT_MS) { line ->
                    if (log.length < 16_000) log.append(line).append('\n')
                    if (line.isNotBlank()) harnessMessage = line.take(160)
                }
                val problem = if (exit != 0) "pnpm exited with $exit" else updater.finishNext(target)
                if (problem != null) {
                    DshLog.w(TAG, "harness update to $target failed: $problem\n${log.takeLast(4000)}")
                    slots.next.deleteRecursively()
                    harnessStatus = "error"
                    harnessMessage = "Update failed: $problem"
                    return@thread
                }
                if (!engine.activateHarnessUpdate(target)) {
                    harnessStatus = "error"
                    harnessMessage = "Could not switch to the new harness; the current one is unchanged."
                    return@thread
                }
                DshLog.i(TAG, "harness $target installed; restarting (previous kept for rollback)")
                harnessStatus = "idle"
                harnessMessage = ""
            } catch (t: Throwable) {
                DshLog.w(TAG, "harness update crashed: ${t.message}")
                harnessStatus = "error"
                harnessMessage = "Update failed: ${t.message}"
                // Activation recovers under the engine lifecycle gate. Preserve every slot if recovery itself failed;
                // an incomplete staging directory is harmless and the next preparation replaces it.
            }
        }
    }

    /** Downloads the release APK of this flavor, checks its SHA-256 and opens the system installer. */
    fun updateApp(activity: Activity) {
        val rel = release
        if (rel == null) { check(activity) { if (release != null) updateApp(activity) }; return }
        if (appStatus == "downloading") return
        if (!AppUpdater.isUpdate(rel, BuildConfig.VERSION_NAME)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            appStatus = "error"
            appMessage = "Allow DSH to install apps, then retry the update here."
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        appStatus = "downloading"
        appMessage = ""
        appProgress = 0
        thread(name = "app-update") {
            try {
                val ready = downloadedApk?.takeIf { it.isFor(rel) }
                if (ready != null && ready.verify() && identityProblem(activity, ready.file) == null) {
                    appStatus = "ready"
                    appProgress = 100
                    activity.runOnUiThread { install(activity, ready.file) }
                    return@thread
                }
                // A previously downloaded APK that is missing, changed or no longer matches this app is not reusable.
                ready?.file?.delete()
                downloadedApk = null
                val dir = File(activity.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, rel.apkName)
                val expected = rel.sumsUrl?.let { AppUpdater.sha256For(httpGet(it), rel.apkName) }
                    ?: throw IOException("the release has no checksum for ${rel.apkName}")
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = open(rel.apkUrl)
                try {
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.apkSize
                    conn.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0) appProgress = ((done * 100) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                } finally { conn.disconnect() }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) { apk.delete(); throw IOException("checksum mismatch") }
                // The checksum only says the archive is what the release published. It can still be the other flavor
                // or be signed with another key; verify identity before the installer is allowed to see it.
                if (identityProblem(activity, apk) != null) {
                    apk.delete()
                    appStatus = "error"
                    appMessage = ApkIdentity.INVALID_MESSAGE
                    appProgress = -1
                    return@thread
                }
                downloadedApk = AppUpdater.DownloadedApk(rel, apk, expected)
                appStatus = "ready"
                appProgress = 100
                activity.runOnUiThread { install(activity, apk) }
            } catch (t: Throwable) {
                DshLog.w(TAG, "app update failed: ${t.message}")
                appStatus = "error"
                appMessage = "Download failed: ${t.message}"
                appProgress = -1
            }
        }
    }

    /**
     * Null when [apk] is this app: same package name and signed by one of the installed app's current signers.
     * Everything is extracted here (Android APIs); the decision itself is [ApkIdentity], tested on the JVM.
     */
    private fun identityProblem(activity: Activity, apk: File): String? {
        val (archivePackage, archiveSigners) = archiveIdentity(activity, apk)
        val problem = ApkIdentity.problem(
            activity.packageName, installedSigners(activity), archivePackage, archiveSigners,
        )
        if (problem != null) DshLog.w(TAG, "rejected update APK: $problem")
        return problem
    }

    /** The signer digests of the installed app, key-rotation history included; empty when Android cannot report them. */
    private fun installedSigners(activity: Activity): Set<String> = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, signatureFlags())
        signerDigests(info).toSet()
    }.getOrDefault(emptySet())

    /** The package name and signer digests of the archive at [apk]; `(null, empty)` when it cannot be read as an APK. */
    private fun archiveIdentity(activity: Activity, apk: File): Pair<String?, Set<String>> = runCatching {
        val info = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            ?: return@runCatching null to emptySet<String>()
        // Some PackageManager implementations only expose certificates once the archive path is on the ApplicationInfo.
        info.applicationInfo?.let { it.sourceDir = apk.absolutePath; it.publicSourceDir = apk.absolutePath }
        info.packageName to signerDigests(info).toSet()
    }.getOrDefault(null to emptySet())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): List<String> {
        val certificates: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            // Sideloaded updates may rotate the key: accept anything in the installed app's history.
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return certificates.orEmpty().mapNotNull { it?.toByteArray() }.map { ApkIdentity.certificateSha256(it) }
    }

    private fun install(activity: Activity, apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }.onFailure { appStatus = "error"; appMessage = "No installer: ${it.message}" }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "DSH-Android/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "*/*")
        if (conn.responseCode !in 200..299) { conn.disconnect(); throw IOException("HTTP ${conn.responseCode} for $url") }
        return conn
    }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try { return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { conn.disconnect() }
    }
}
