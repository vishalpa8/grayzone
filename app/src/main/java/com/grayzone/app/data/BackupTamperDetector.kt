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

    fun isTampered(backupExists: Boolean): Boolean {
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
        // F-023: Hostile UX removed. We just log the tamper event instead of punishing the user.
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.PREFS,
            "Tamper detected: State backup wiped while Keystore key survived. Lockdown skipped."
        )
    }
}
