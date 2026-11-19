package com.akira.island.service

/** Lets one app's notification banner through at most once per cooldown, so a burst of alerts doesn't take over the island. */
internal class NotificationCooldown {
    private val last = HashMap<String, Long>()

    /** True (and remembers [now]) when [pkg] hasn't been shown within [cooldownMs]; a zero cooldown never holds anything back. */
    fun allows(pkg: String, now: Long, cooldownMs: Long): Boolean {
        if (cooldownMs <= 0) return true
        val previous = last[pkg]
        if (previous != null && now - previous < cooldownMs) return false
        last[pkg] = now
        return true
    }
}
