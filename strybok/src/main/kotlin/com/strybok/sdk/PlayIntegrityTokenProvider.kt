package com.strybok.sdk

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Ready-made [StrybokIntegrityTokenProvider] on top of the Google Play Integrity **standard**
 * API.
 *
 * **Your app must add the library itself**; the SDK only compiles against it and does not bring
 * it along:
 *
 * ```kotlin
 * dependencies {
 *     implementation("com.google.android.play:integrity:1.4.0")
 * }
 * ```
 *
 * Without the library on the classpath this provider reports "cannot attest" for every request
 * (and says so once in logcat); it never throws. The same happens on devices without Google
 * Play services or without the Play Store, and for builds that were not installed from Google
 * Play — see the limits in `docs/link-creation-security.md`.
 *
 * Nothing happens when the provider is created. The token provider of Play Integrity is
 * prepared on the first [Strybok.createLink] call that needs a proof, kept for the life of this
 * object, and prepared again once when Google reports that it has expired.
 *
 * @param context any context; the application context is used.
 * @param cloudProjectNumber the **number** (not the id) of the Google Cloud project linked to
 *   the app in the Play Console.
 * @param timeoutMillis time allowed for each call to Google Play (preparing, and requesting a
 *   token).
 */
public class PlayIntegrityTokenProvider @JvmOverloads constructor(
    context: Context,
    private val cloudProjectNumber: Long,
    private val timeoutMillis: Long = 10_000,
) : StrybokIntegrityTokenProvider {
    private val appContext: Context = context.applicationContext ?: context
    private val lock = Any()

    /** Guarded by [lock]. Only created when the library is present. */
    private var calls: PlayCalls? = null

    override fun requestIntegrityToken(requestHash: String): String? {
        if (!isLibraryAvailable()) {
            warnMissingLibraryOnce()
            return null
        }
        val calls = synchronized(lock) {
            calls ?: PlayCalls(appContext, cloudProjectNumber, timeoutMillis).also { calls = it }
        }
        return calls.request(requestHash)
    }

    /**
     * Everything that names a class of Google's libraries lives here, so those classes are only
     * loaded after [isLibraryAvailable] said they exist.
     */
    private class PlayCalls(
        context: Context,
        private val cloudProjectNumber: Long,
        private val timeoutMillis: Long,
    ) {
        private val manager: StandardIntegrityManager = IntegrityManagerFactory.createStandard(context)

        /** Guarded by `this`. */
        private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

        @Synchronized
        fun request(requestHash: String): String? {
            return try {
                token(requestHash)
            } catch (error: StandardIntegrityException) {
                if (error.errorCode != StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) throw error
                // Google's provider has expired: prepare a new one, once.
                provider = null
                token(requestHash)
            }
        }

        private fun token(requestHash: String): String? {
            val current = provider ?: await(
                manager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build(),
                ),
            ).also { provider = it }
            return await(
                current.request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build(),
                ),
            ).token()
        }

        /** Blocks for the result; a failure is thrown as Google reported it. */
        private fun <T> await(task: Task<T>): T = try {
            Tasks.await(task, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception) ?: error
        }
    }

    public companion object {
        private const val TAG = "Strybok"

        @Volatile
        private var warned = false

        /**
         * Whether `com.google.android.play:integrity` is part of this app. `false` means
         * [PlayIntegrityTokenProvider] cannot attest and the app must add the dependency.
         */
        @JvmStatic
        public fun isLibraryAvailable(): Boolean = try {
            val loader = PlayIntegrityTokenProvider::class.java.classLoader
            Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory", false, loader)
            Class.forName("com.google.android.gms.tasks.Tasks", false, loader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

        private fun warnMissingLibraryOnce() {
            if (warned) return
            warned = true
            try {
                Log.w(
                    TAG,
                    "PlayIntegrityTokenProvider is configured but com.google.android.play:integrity " +
                        "is not on the classpath. Add it to the app's dependencies; until then " +
                        "links are created without attestation.",
                )
            } catch (_: RuntimeException) {
                // android.util.Log is a stub in JVM unit tests.
            }
        }
    }
}
