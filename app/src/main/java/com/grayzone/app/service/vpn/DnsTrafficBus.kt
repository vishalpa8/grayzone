package com.grayzone.app.service.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bus that the VPN DNS loop writes to and the UI reads from.
 *
 * Design constraints:
 *  - Zero disk I/O, zero database writes — this is purely ephemeral UI data.
 *  - Fixed capacity of [MAX_EVENTS] so memory is bounded regardless of traffic.
 *  - Single writer (VPN IO thread), many readers (UI). Uses a StateFlow so
 *    Compose collectors only recompose when the list reference changes.
 *  - The list is replaced (not mutated) on each update so StateFlow equality
 *    check fires correctly and collectors get the new snapshot.
 */
object DnsTrafficBus {

    const val MAX_EVENTS = 50

    data class DnsEvent(
        val domain: String,
        val status: Status,
        val timestampMs: Long = System.currentTimeMillis(),
        val id: String = java.util.UUID.randomUUID().toString()
    ) {
        enum class Status { ALLOWED, BLOCKED_AD, BLOCKED_DOH }
    }

    private val _events = MutableStateFlow<List<DnsEvent>>(emptyList())
    val events: StateFlow<List<DnsEvent>> = _events.asStateFlow()

    /** Called from the VPN IO thread — thread-safe via @Synchronized. */
    @Synchronized
    fun emit(event: DnsEvent) {
        val current = _events.value
        // Prepend newest at front; drop tail beyond MAX_EVENTS in one shot.
        val next = if (current.size >= MAX_EVENTS) {
            buildList(MAX_EVENTS) {
                add(event)
                addAll(current.subList(0, MAX_EVENTS - 1))
            }
        } else {
            buildList(current.size + 1) {
                add(event)
                addAll(current)
            }
        }
        _events.value = next
    }

    /** Clear the feed (e.g. when VPN stops). */
    fun clear() {
        _events.value = emptyList()
    }
}
