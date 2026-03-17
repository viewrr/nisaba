package com.nisaba.api.auth

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory session store for SID-based authentication.
 * Mimics the qBittorrent SID cookie approach.
 */
@Component
class SessionStore {
    private val sessions = ConcurrentHashMap<String, Instant>()

    fun createSession(): String {
        val sid = UUID.randomUUID().toString()
        sessions[sid] = Instant.now()
        return sid
    }

    fun isValid(sid: String?): Boolean {
        if (sid == null) return false
        return sessions.containsKey(sid)
    }

    fun invalidate(sid: String) {
        sessions.remove(sid)
    }
}
