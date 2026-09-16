package com.grayzone.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator

object BackupTamperDetector {
    private const val KEY_ALIAS = "grayzone_tamper_anchor"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun markBackupCreated() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            // Ignore keystore issues
        }
    }

    fun isTampered(context: Context, backupExists: Boolean): Boolean {
        if (backupExists) return false
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                // Keystore key exists, but backup doesn't. 
                // This means they deleted the backup file (via USB/MTP) and cleared data, 
                // but didn't factory reset (which would wipe the Keystore).
                return true
            }
        } catch (e: Exception) {
            // Ignore keystore issues
        }
        return false
    }

    fun executeTamperLockdown(prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val tomorrow = now + 24 * 60 * 60 * 1000L
        // Lock settings and play store for 24 hours as punishment
        prefs.edit()
            .putBoolean(com.grayzone.app.PrefsKeys.GRAYZONE_ENABLED, true)
            .putStringSet(com.grayzone.app.PrefsKeys.MONITORED_APPS, setOf("com.android.settings", "com.android.vending"))
            .putLong(com.grayzone.app.PrefsKeys.LOCKED_UNTIL + "com.android.settings", tomorrow)
            .putLong(com.grayzone.app.PrefsKeys.LOCKED_UNTIL + "com.android.vending", tomorrow)
            .commit()
    }
}
