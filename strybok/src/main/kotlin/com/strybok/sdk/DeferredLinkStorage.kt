package com.strybok.sdk

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two values the deferred-link check keeps: `attempts` and `done`, one tiny file each in
 * [directory]. Nothing else is ever written — not the referrer, not the click id.
 *
 * Files instead of `SharedPreferences` because the directory used on a device is inside
 * `Context.getNoBackupFilesDir()`: Android's automatic backup would otherwise restore `done`
 * into a fresh installation, which is exactly the launch the check exists for.
 */
internal class DeferredLinkFiles(private val directory: () -> File?) {
    fun attempts(): Int {
        val text = read(ATTEMPTS) ?: return 0
        // Unreadable content counts as "all attempts used": never more checks than allowed.
        return text.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
    }

    fun storeAttempts(value: Int): Boolean = write(ATTEMPTS, value.toString())

    fun isDone(): Boolean = file(DONE)?.exists() == true

    fun storeDone(): Boolean = write(DONE, "1")

    private fun file(name: String): File? = directory()?.let { File(it, name) }

    private fun read(name: String): String? = try {
        file(name)?.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()
    } catch (_: Exception) {
        "" // Exists but cannot be read.
    }

    /** Writes through a temporary file, so a crash leaves the old value or the new one. */
    private fun write(name: String, value: String): Boolean = try {
        val directory = directory()
        if (directory == null || !(directory.isDirectory || directory.mkdirs())) {
            false
        } else {
            val target = File(directory, name)
            val temporary = File(directory, "$name.tmp")
            temporary.writeText(value, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(value, Charsets.UTF_8)
                temporary.delete()
            }
            target.exists()
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val ATTEMPTS = "attempts"
        const val DONE = "done"
    }
}

/** [DeferredLinkPlatform] of a real app. Nothing is touched before the check asks. */
internal class AndroidDeferredLinkPlatform(context: Context) : DeferredLinkPlatform {
    private val appContext: Context = context.applicationContext ?: context
    private val files = DeferredLinkFiles { File(appContext.noBackupFilesDir, "com.strybok.sdk/deferred") }

    override val packageName: String get() = appContext.packageName

    override fun wasUpdatedSinceInstall(): Boolean = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.firstInstallTime != info.lastUpdateTime
    } catch (_: Exception) {
        false
    }

    override fun attempts(): Int = files.attempts()

    override fun storeAttempts(value: Int): Boolean = files.storeAttempts(value)

    override fun isDone(): Boolean = files.isDone()

    override fun storeDone(): Boolean = files.storeDone()

    override fun claimProcess(): Boolean = claimed.compareAndSet(false, true)

    private companion object {
        /** One check per process, however many [Strybok] instances the app creates. */
        val claimed = AtomicBoolean(false)
    }
}
