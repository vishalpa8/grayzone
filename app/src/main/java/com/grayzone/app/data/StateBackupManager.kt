package com.grayzone.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.PrefsKeys
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anti-tamper state persistence for Grayzone.
 *
 * The whole point of a self-control app is that a user, in a weak moment, may try to escape their
 * own locks/limits by clearing the app's data or cache. Clearing data wipes the entire sandbox
 * (SharedPreferences, the Room DB, filesDir, cache, and Android/data/<pkg>), so any recovery copy
 * MUST live OUTSIDE the sandbox.
 *
 * This writes an AES-256-GCM encrypted JSON snapshot of every [PrefsKeys.PREFS_NAME] entry into a
 * hidden folder at the root of shared storage (`/sdcard/.grayzone/backup.dat`) using the raw File
 * API. This requires all-files access (MANAGE_EXTERNAL_STORAGE, or legacy WRITE on API 29). The
 * payload is encrypted so a user who locates the file can neither read nor edit it.
 *
 * Why the raw File API and NOT MediaStore: MediaStore cannot create files in a hidden/.nomedia
 * folder at all, and — decisively — a MediaStore file loses its owner attribution when app data is
 * cleared, so afterwards the app cannot read it back without READ_EXTERNAL_STORAGE (which no longer
 * exists on Android 13+). All-files access reads the file directly regardless of attribution, so it
 * is the only mechanism that both hides the file and reliably restores after a clear.
 *
 * Why the `Android/` folder is unusable: everything under `Android/` is app-scoped and is wiped on
 * the very clear-data action we defend against (or is inaccessible for other apps on Android 11+).
 *
 * Honest limitations:
 *  - Needs all-files access. If a device revokes that permission on clear-data, restore cannot run
 *    until the user re-grants it; [restoreIfWiped] is therefore retried whenever access returns
 *    (see the onResume hook in MainActivity). If the user refuses, no restore is possible — this is
 *    inherent to hiding the file.
 *  - Does not survive a factory reset (ANDROID_ID and shared storage are both wiped then).
 *
 * The snapshot is stamped with [Settings.Secure.ANDROID_ID] (stable across clear-data and reinstall,
 * reset only on factory reset) so a server-side sync keyed by that id can be added later without
 * changing the on-disk format.
 */
object StateBackupManager {

    private val restoreLock = Any()

    /**
     * Set after a successful [restoreIfWiped] in this process so [com.grayzone.app.MainActivity]
     * can skip repeating the binder + decrypt path on every onResume (F-044).
     */
    @Volatile
    var restoredThisProcess: Boolean = false
        private set

    /**
     * True while a restore is applying. Writers ([writeBackup]) and preference listeners must not
     * flush mid-restore or they can race and persist a half-applied snapshot.
     */
    @Volatile
    var restoreInProgress: Boolean = false
        private set

    /**
     * True when this process applied a backup that had VPN intent on. MainScreen may offer the
     * system VPN consent dialog at most once (see [VpnRestoreGate]).
     */
    @Volatile
    var restoredVpnIntent: Boolean = false
        private set

    private const val SCHEMA_VERSION = 1

    /** Exact keys and prefixes that may be restored. Unknown keys are dropped (F-043). */
    private val ALLOWED_EXACT_KEYS: Set<String> = setOf(
        PrefsKeys.GRAYZONE_ENABLED, PrefsKeys.THEME_MODE,
        // F-090: never restore prompt-suppressor flags — wipe+restore must re-prompt permissions.
        PrefsKeys.ONBOARDING_COMPLETED,
        PrefsKeys.WAIT_SECONDS, PrefsKeys.SESSION_MINUTES,
        PrefsKeys.LOCKOUT_MINUTES, PrefsKeys.MONITORED_APPS, PrefsKeys.DEFAULT_APPS_SEEDED,
        PrefsKeys.SCHEDULE_RULES_JSON, PrefsKeys.FOCUS_MODE_ACTIVE, PrefsKeys.FOCUS_MODE_UNTIL,
        PrefsKeys.FOCUS_STARTED_ELAPSED, PrefsKeys.FOCUS_DURATION_MS,
        PrefsKeys.BREAK_UNTIL, PrefsKeys.BREAK_USED_DATE,
        PrefsKeys.BREAK_STARTED_ELAPSED, PrefsKeys.BREAK_DURATION_MS_KEY,
        PrefsKeys.DAILY_RESET_ZONE_ID, PrefsKeys.VPN_BYPASS_APPS,
        PrefsKeys.CUSTOM_PROMPTS_JSON, PrefsKeys.USE_CUSTOM_PROMPTS_ONLY,
        PrefsKeys.CURRENT_STREAK, PrefsKeys.LONGEST_STREAK, PrefsKeys.STREAK_LAST_DATE,
        PrefsKeys.TOTAL_SESSIONS_BLOCKED, PrefsKeys.TOTAL_TIME_SAVED_MINS,
        PrefsKeys.ACHIEVEMENTS_JSON,
        PrefsKeys.VPN_ENABLED,
        PrefsKeys.VPN_HOLD_STARTED_WALL, PrefsKeys.VPN_HOLD_STARTED_ELAPSED,
        PrefsKeys.DAILY_RESET_DATE + "runtime"
    )

    private val ALLOWED_PREFIXES: List<String> = listOf(
        PrefsKeys.ACTIVE_UNTIL, PrefsKeys.LOCKED_UNTIL, PrefsKeys.REMAINING_MILLIS,
        PrefsKeys.ACTIVE_STARTED_ELAPSED, PrefsKeys.ACTIVE_DURATION_MS,
        PrefsKeys.LOCKED_STARTED_ELAPSED, PrefsKeys.LOCKED_DURATION_MS,
        PrefsKeys.PER_APP_WAIT_SECONDS, PrefsKeys.PER_APP_SESSION_MINUTES,
        PrefsKeys.PER_APP_LOCKOUT_MINUTES, PrefsKeys.PER_APP_HAS_CUSTOM,
        PrefsKeys.DAILY_BUDGET_MINUTES, PrefsKeys.DAILY_USED_MILLIS, PrefsKeys.DAILY_RESET_DATE
    )

    private fun isAllowedKey(key: String): Boolean =
        key in ALLOWED_EXACT_KEYS || ALLOWED_PREFIXES.any { key.startsWith(it) }

    // Preferred hidden location: a dot-prefixed folder at the root of shared storage. Writable only
    // with all-files access (MANAGE_EXTERNAL_STORAGE / legacy WRITE) via the raw File API — MediaStore
    // cannot touch hidden folders. Survives clear-data and is invisible to the gallery/casual browsing.
    private const val HIDDEN_DIR = ".grayzone"
    private const val FILE_NAME = "backup.dat"
    /** High-water generation marker so an older backup file cannot roll locks back (F-045). */
    private const val GEN_MARKER_NAME = "backup.gen"

    /**
     * How often the app-wide coarse flush loop re-checks the dirty flag. Writes only happen when
     * something actually changed (see [GrayzoneApplication]); this cadence bounds how stale a
     * background/service-driven change can be, without doing any I/O when idle. Deliberately coarse
     * to keep flash writes and CPU wakeups negligible for battery.
     */
    const val FLUSH_INTERVAL_MS = 120_000L

    // ── Encryption ("lock") ─────────────────────────────────────────────────────
    // The blob is AES-256-GCM encrypted so a user who finds the file cannot read the state, and any
    // hand-edit fails the GCM auth tag (tamper-evident → we refuse to restore garbage).
    //
    // Key derivation deliberately does NOT use the Android Keystore: Keystore keys are destroyed
    // when app data is cleared, i.e. by the exact action we defend against, which would make the
    // backup permanently undecryptable. Instead the key is derived from ANDROID_ID (stable across
    // clear-data, reset only on factory reset) mixed with an in-APK pepper. This is obfuscation-grade
    // — enough to lock out a curious user, not a reverse-engineer with the APK — which matches the
    // threat model (stop yourself from cheating, not defeat a determined attacker).
    private const val KEY_PEPPER = "grayzone::state::v1::b7Q9x2Lm"
    private const val CRYPTO_VERSION: Byte = 1
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /**
     * Restores **all** allowed control state from the external backup when the sandbox looks wiped
     * or a previous attempt was deferred ([PrefsKeys.RESTORE_PENDING]).
     *
     * Automatic + race-safe:
     * - Single-flight under [restoreLock] (Application + Activity cannot double-apply).
     * - If all-files access is missing, marks [PrefsKeys.RESTORE_PENDING] and returns — retries on
     *   the next call once access is granted (does not confuse "no permission" with "no backup").
     * - Pending restore still runs even if the user raced through onboarding and set
     *   [PrefsKeys.ONBOARDING_COMPLETED] first — clear-then-commit overwrites that sandbox.
     *
     * @return true if a backup was found and applied this call.
     */
    fun restoreIfWiped(context: Context): Boolean {
        synchronized(restoreLock) {
            if (restoredThisProcess) return false
            restoreInProgress = true
            try {
                val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val wiped = !prefs.contains(PrefsKeys.ONBOARDING_COMPLETED)
                val pending = prefs.getBoolean(PrefsKeys.RESTORE_PENDING, false)
                if (!wiped && !pending) return false

                if (!hasAllFilesAccess(context)) {
                    // Defer — do not claim "no backup"; we simply cannot read `.grayzone` yet.
                    val editor = prefs.edit().putBoolean(PrefsKeys.RESTORE_PENDING, true)
                    if (!pending) {
                        // First defer after wipe: allow one fresh settings launch (not every resume).
                        editor.putBoolean(PrefsKeys.ALL_FILES_PROMPTED, false)
                    }
                    editor.commit()
                    GrayzoneLogger.i(
                        LogComponent.BOOT,
                        "Backup restore deferred — waiting for all-files access"
                    )
                    return false
                }

                val json = readBackup(context)
                if (json == null) {
                    if (BackupTamperDetector.isTampered(context, backupExists = false)) {
                        GrayzoneLogger.w(
                            LogComponent.BOOT,
                            "TAMPERING DETECTED: Keystore key exists but backup file is missing! User cleared data via USB wipe."
                        )
                        BackupTamperDetector.executeTamperLockdown(prefs)
                    } else {
                        GrayzoneLogger.i(
                            LogComponent.BOOT,
                            "No external backup file to restore (sandbox wiped or first install)"
                        )
                    }
                    prefs.edit().remove(PrefsKeys.RESTORE_PENDING).commit()
                    return false
                }

                val restored = applyInto(prefs, json, clearFirst = true)
                if (restored) {
                    // clearFirst removed RESTORE_PENDING; keep it cleared explicitly for clarity.
                    prefs.edit().remove(PrefsKeys.RESTORE_PENDING).commit()
                    restoredThisProcess = true
                    restoredVpnIntent = prefs.getBoolean(PrefsKeys.VPN_ENABLED, false)
                    GrayzoneLogger.i(
                        LogComponent.BOOT,
                        "State restored from external backup automatically" +
                            if (restoredVpnIntent) " (VPN intent restored)" else ""
                    )
                } else {
                    // Schema/generation refuse won't succeed on retry — drop pending so writes
                    // are not blocked forever by a permanently unusable file.
                    prefs.edit().remove(PrefsKeys.RESTORE_PENDING).commit()
                }
                return restored
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.BOOT, "State restore failed: ${e.message}", e)
                // Transient failure (I/O, decrypt race) — keep pending and retry on next resume.
                try {
                    context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PrefsKeys.RESTORE_PENDING, true).commit()
                } catch (_: Exception) { /* ignore */ }
                return false
            } finally {
                restoreInProgress = false
            }
        }
    }

    /**
     * True when the app can use the raw File API on shared storage (all-files access). Required to
     * read/write the hidden `.grayzone` folder; MediaStore cannot touch hidden directories.
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * True when a usable backup file is already present. Lets startup write a one-time baseline only
     * when none exists yet, instead of re-encrypting on every process start (services can restart the
     * process often); ongoing changes are captured by the dirty-flag flushes in [GrayzoneApplication].
     */
    fun backupExists(context: Context): Boolean =
        hasAllFilesAccess(context) && hiddenFile().exists()

    /**
     * Serializes all preferences and writes the encrypted snapshot to the hidden file, overwriting
     * any previous snapshot. Requires all-files access; without it there is no reliable place to
     * write (a MediaStore file cannot be read back after a data clear because ownership attribution
     * is lost), so the write is skipped and logged. Safe to call frequently; failures are logged.
     */
    /**
     * @return true when a snapshot was written successfully; false when skipped or failed
     *   (caller should keep any dirty flag set so a later flush can retry — F-063).
     */
    fun writeBackup(context: Context): Boolean {
        if (restoreInProgress) {
            GrayzoneLogger.i(LogComponent.BOOT, "State backup skipped — restore in progress")
            return false
        }
        if (!hasAllFilesAccess(context)) {
            GrayzoneLogger.i(LogComponent.BOOT, "State backup skipped — all-files access not granted")
            return false
        }
        if (androidId(context).isNullOrEmpty()) {
            // F-046: empty ANDROID_ID would derive a constant key shared by every install.
            GrayzoneLogger.w(LogComponent.BOOT, "State backup skipped — ANDROID_ID unavailable")
            return false
        }
        return try {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            // Never overwrite the on-disk backup with a raced empty/partial sandbox while a
            // deferred restore still needs to apply the previous install's snapshot.
            if (prefs.getBoolean(PrefsKeys.RESTORE_PENDING, false)) {
                GrayzoneLogger.i(LogComponent.BOOT, "State backup skipped — restore pending")
                return false
            }
            val plaintext = serialize(context, prefs.all).toByteArray(Charsets.UTF_8)
            val bytes = encrypt(context, plaintext)
            val file = hiddenFile()
            file.parentFile?.mkdirs()
            file.writeBytes(bytes) // single fixed path → overwrites, never duplicates
            GrayzoneLogger.i(
                LogComponent.BOOT,
                "State backup written (${bytes.size} bytes) -> ${file.absolutePath}"
            )
            true
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.BOOT, "State backup write failed", e)
            false
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun hiddenFile(): File =
        File(Environment.getExternalStorageDirectory(), "$HIDDEN_DIR/$FILE_NAME")

    private fun readBackup(context: Context): String? {
        if (!hasAllFilesAccess(context)) return null // hidden file unreadable without the permission
        val file = hiddenFile()
        if (!file.exists()) return null
        val blob = file.readBytes()
        return try {
            decrypt(context, blob)?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // Wrong key (e.g. factory reset changed ANDROID_ID) or a tampered file → refuse it.
            GrayzoneLogger.w(LogComponent.BOOT, "Backup decryption failed; ignoring file", e)
            null
        }
    }

    private fun generationMarkerFile(): File =
        File(Environment.getExternalStorageDirectory(), "$HIDDEN_DIR/$GEN_MARKER_NAME")

    private fun readHighWaterGeneration(): Long = try {
        val f = generationMarkerFile()
        if (!f.exists()) 0L else f.readText().trim().toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun writeHighWaterGeneration(gen: Long) {
        try {
            val f = generationMarkerFile()
            f.parentFile?.mkdirs()
            f.writeText(gen.toString())
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.BOOT, "Failed to write backup generation marker", e)
        }
    }

    private fun serialize(context: Context, all: Map<String, Any?>): String {
        BackupTamperDetector.markBackupCreated()
        val nextGen = readHighWaterGeneration() + 1L
        writeHighWaterGeneration(nextGen)
        val root = com.google.gson.JsonObject().apply {
            addProperty("schema", SCHEMA_VERSION)
            addProperty("androidId", androidId(context).orEmpty())
            addProperty("savedAt", System.currentTimeMillis())
            addProperty("generation", nextGen)
        }
        val entries = com.google.gson.JsonObject()
        for ((key, value) in all) {
            // F-090: do not persist prompt-suppressor flags into the anti-tamper backup.
            if (key == PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS ||
                key == PrefsKeys.ALL_FILES_PROMPTED
            ) {
                continue
            }
            val entry = JsonObject()
            when (value) {
                is Boolean -> { entry.addProperty("t", "bool"); entry.addProperty("v", value) }
                is Int -> { entry.addProperty("t", "int"); entry.addProperty("v", value) }
                is Long -> { entry.addProperty("t", "long"); entry.addProperty("v", value) }
                is Float -> { entry.addProperty("t", "float"); entry.addProperty("v", value) }
                is String -> { entry.addProperty("t", "string"); entry.addProperty("v", value) }
                is Set<*> -> {
                    entry.addProperty("t", "stringset")
                    val arr = JsonArray()
                    value.forEach { if (it != null) arr.add(it.toString()) }
                    entry.add("v", arr)
                }
                else -> continue // null or unsupported type
            }
            entries.add(key, entry)
        }
        root.add("entries", entries)
        return root.toString()
    }

    private fun applyInto(
        prefs: SharedPreferences,
        json: String,
        clearFirst: Boolean,
    ): Boolean {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.get("schema")?.asInt
            if (schema != SCHEMA_VERSION) {
                GrayzoneLogger.w(
                    LogComponent.BOOT,
                    "Backup schema mismatch (got $schema, want $SCHEMA_VERSION); refusing restore"
                )
                return false
            }
            // F-045: refuse a file older than the high-water marker (rollback attack).
            val generation = root.get("generation")?.asLong ?: 0L
            val highWater = readHighWaterGeneration()
            if (generation > 0L && highWater > 0L && generation < highWater) {
                GrayzoneLogger.w(
                    LogComponent.BOOT,
                    "Backup generation $generation is older than high-water $highWater; refusing restore"
                )
                return false
            }
            val entries = root.getAsJsonObject("entries") ?: return false
            // One atomic commit: optional clear + all restores, so readers never see a half mix.
            val editor = prefs.edit()
            if (clearFirst) editor.clear()
            for ((key, element) in entries.entrySet()) {
                if (!isAllowedKey(key)) continue
                try {
                    val obj = element.asJsonObject
                    val type = obj.get("t")?.asString ?: continue
                    // Expected types for known keys — refuse type drift that would crash getters (F-060).
                    val expected = expectedType(key)
                    if (expected != null && expected != type) {
                        GrayzoneLogger.w(
                            LogComponent.BOOT,
                            "Backup key $key has type $type, expected $expected; skipping"
                        )
                        continue
                    }
                    when (type) {
                        "bool" -> editor.putBoolean(key, obj.get("v").asBoolean)
                        "int" -> editor.putInt(key, obj.get("v").asInt)
                        "long" -> editor.putLong(key, obj.get("v").asLong)
                        "float" -> editor.putFloat(key, obj.get("v").asFloat)
                        "string" -> editor.putString(key, obj.get("v").asString)
                        "stringset" -> {
                            val set = obj.getAsJsonArray("v").map { it.asString }.toSet()
                            editor.putStringSet(key, set)
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    GrayzoneLogger.w(
                        LogComponent.BOOT,
                        "Backup entry $key skipped — malformed value: ${e.message}"
                    )
                }
            }
            // commit(), not apply(): restored values must be durable before services/UI read them.
            val ok = editor.commit()
            if (ok && generation > highWater) writeHighWaterGeneration(generation)
            ok
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.BOOT, "Backup apply failed; leaving prefs untouched", e)
            false
        }
    }

    private fun expectedType(key: String): String? = when {
        key == PrefsKeys.GRAYZONE_ENABLED ||
            key == PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS ||
            key == PrefsKeys.ONBOARDING_COMPLETED ||
            key == PrefsKeys.ALL_FILES_PROMPTED ||
            key == PrefsKeys.DEFAULT_APPS_SEEDED ||
            key == PrefsKeys.FOCUS_MODE_ACTIVE ||
            key == PrefsKeys.USE_CUSTOM_PROMPTS_ONLY ||
            key == PrefsKeys.VPN_ENABLED ||
            key.startsWith(PrefsKeys.PER_APP_HAS_CUSTOM) -> "bool"
        key == PrefsKeys.WAIT_SECONDS ||
            key == PrefsKeys.SESSION_MINUTES ||
            key == PrefsKeys.LOCKOUT_MINUTES ||
            key == PrefsKeys.CURRENT_STREAK ||
            key == PrefsKeys.LONGEST_STREAK ||
            key == PrefsKeys.TOTAL_SESSIONS_BLOCKED ||
            key == PrefsKeys.TOTAL_TIME_SAVED_MINS ||
            key.startsWith(PrefsKeys.PER_APP_WAIT_SECONDS) ||
            key.startsWith(PrefsKeys.PER_APP_SESSION_MINUTES) ||
            key.startsWith(PrefsKeys.PER_APP_LOCKOUT_MINUTES) ||
            key.startsWith(PrefsKeys.DAILY_BUDGET_MINUTES) -> "int"
        key == PrefsKeys.FOCUS_MODE_UNTIL ||
            key == PrefsKeys.FOCUS_STARTED_ELAPSED ||
            key == PrefsKeys.FOCUS_DURATION_MS ||
            key == PrefsKeys.BREAK_UNTIL ||
            key == PrefsKeys.BREAK_STARTED_ELAPSED ||
            key == PrefsKeys.BREAK_DURATION_MS_KEY ||
            key == PrefsKeys.VPN_HOLD_STARTED_WALL ||
            key == PrefsKeys.VPN_HOLD_STARTED_ELAPSED ||
            key.startsWith(PrefsKeys.ACTIVE_UNTIL) ||
            key.startsWith(PrefsKeys.LOCKED_UNTIL) ||
            key.startsWith(PrefsKeys.REMAINING_MILLIS) ||
            key.startsWith(PrefsKeys.ACTIVE_STARTED_ELAPSED) ||
            key.startsWith(PrefsKeys.ACTIVE_DURATION_MS) ||
            key.startsWith(PrefsKeys.LOCKED_STARTED_ELAPSED) ||
            key.startsWith(PrefsKeys.LOCKED_DURATION_MS) ||
            key.startsWith(PrefsKeys.DAILY_USED_MILLIS) -> "long"
        key == PrefsKeys.MONITORED_APPS || key == PrefsKeys.VPN_BYPASS_APPS -> "stringset"
        key == PrefsKeys.THEME_MODE ||
            key == PrefsKeys.SCHEDULE_RULES_JSON ||
            key == PrefsKeys.CUSTOM_PROMPTS_JSON ||
            key == PrefsKeys.STREAK_LAST_DATE ||
            key == PrefsKeys.ACHIEVEMENTS_JSON ||
            key == PrefsKeys.BREAK_USED_DATE ||
            key == PrefsKeys.DAILY_RESET_ZONE_ID ||
            key.startsWith(PrefsKeys.DAILY_RESET_DATE) -> "string"
        else -> null
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun deriveKey(context: Context): SecretKey {
        val id = androidId(context)
            ?: throw IllegalStateException("ANDROID_ID unavailable — refusing weak backup key")
        val material = "$id|$KEY_PEPPER".toByteArray(Charsets.UTF_8)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(material) // 32 bytes → AES-256
        return SecretKeySpec(keyBytes, "AES")
    }

    /** [version(1)][iv(12)][ciphertext+tag]. */
    private fun encrypt(context: Context, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + ciphertext.size).also { out ->
            out[0] = CRYPTO_VERSION
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
        }
    }

    /** Returns null for an unrecognized format; throws for wrong-key / tampered payloads. */
    private fun decrypt(context: Context, blob: ByteArray): ByteArray? {
        if (blob.size < 1 + GCM_IV_BYTES || blob[0] != CRYPTO_VERSION) return null
        val iv = blob.copyOfRange(1, 1 + GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(1 + GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }
}
