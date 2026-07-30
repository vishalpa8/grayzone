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

    private const val SCHEMA_VERSION = 1

    // Preferred hidden location: a dot-prefixed folder at the root of shared storage. Writable only
    // with all-files access (MANAGE_EXTERNAL_STORAGE / legacy WRITE) via the raw File API — MediaStore
    // cannot touch hidden folders. Survives clear-data and is invisible to the gallery/casual browsing.
    private const val HIDDEN_DIR = ".grayzone"
    private const val FILE_NAME = "backup.dat"

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
     * Restores state from the external backup when the app looks freshly wiped.
     *
     * "Wiped" is detected by the absence of the [PrefsKeys.ONBOARDING_COMPLETED] sentinel: a user
     * who finished setup always has it, so its disappearance means data was cleared (or this is a
     * genuine first launch, in which case there is simply no backup to restore and this is a no-op).
     *
     * Runs synchronously so the restored values are visible before the rest of onCreate — and before
     * any service or the UI — reads preferences. Keep the payload small (control state only).
     *
     * @return true if a backup was found and applied.
     */
    fun restoreIfWiped(context: Context): Boolean = try {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        when {
            prefs.contains(PrefsKeys.ONBOARDING_COMPLETED) -> false // live state present; never clobber
            else -> {
                val json = readBackup(context)
                if (json == null) {
                    GrayzoneLogger.i(
                        LogComponent.BOOT,
                        "Prefs look wiped but no external backup was found to restore"
                    )
                    false
                } else {
                    val restored = applyInto(prefs, json)
                    if (restored) {
                        GrayzoneLogger.i(
                            LogComponent.BOOT,
                            "State restored from external backup after a data/cache wipe"
                        )
                    }
                    restored
                }
            }
        }
    } catch (e: Exception) {
        GrayzoneLogger.w(LogComponent.BOOT, "State restore failed: ${e.message}")
        false
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
    fun writeBackup(context: Context) {
        if (!hasAllFilesAccess(context)) {
            GrayzoneLogger.i(LogComponent.BOOT, "State backup skipped — all-files access not granted")
            return
        }
        try {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val plaintext = serialize(context, prefs.all).toByteArray(Charsets.UTF_8)
            val bytes = encrypt(context, plaintext)
            val file = hiddenFile()
            file.parentFile?.mkdirs()
            file.writeBytes(bytes) // single fixed path → overwrites, never duplicates
            GrayzoneLogger.i(
                LogComponent.BOOT,
                "State backup written (${bytes.size} bytes) -> ${file.absolutePath}"
            )
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.BOOT, "State backup write failed", e)
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

    private fun serialize(context: Context, all: Map<String, Any?>): String {
        val root = JsonObject().apply {
            addProperty("schema", SCHEMA_VERSION)
            addProperty("androidId", androidId(context))
            addProperty("savedAt", System.currentTimeMillis())
        }
        val entries = JsonObject()
        for ((key, value) in all) {
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

    private fun applyInto(prefs: SharedPreferences, json: String): Boolean {
        val root = JsonParser.parseString(json).asJsonObject
        val entries = root.getAsJsonObject("entries") ?: return false
        val editor = prefs.edit()
        for ((key, element) in entries.entrySet()) {
            val obj = element.asJsonObject
            when (obj.get("t")?.asString) {
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
        }
        // commit(), not apply(): the restored values must be durably visible before the rest of
        // onCreate (and any service/UI) reads them on this same launch.
        return editor.commit()
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun deriveKey(context: Context): SecretKey {
        val material = "${androidId(context)}|$KEY_PEPPER".toByteArray(Charsets.UTF_8)
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
