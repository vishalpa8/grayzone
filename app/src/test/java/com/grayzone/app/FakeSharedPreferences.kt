package com.grayzone.app

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] for pure JVM unit tests (no Robolectric).
 * Only the read/write surface used by the guard logic under test is exercised,
 * but the full interface is implemented so it can be passed anywhere.
 */
class FakeSharedPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {

    private val map = HashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { pending[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { removals.add(key!!) }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }

        private fun flush() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
