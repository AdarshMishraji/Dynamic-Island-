package com.akira.island.core

/** Half-typed replies, per message, so a call or another card taking over the island doesn't lose them. */
object ReplyDrafts {
    private val drafts = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 20
    }

    @Synchronized fun get(key: String): String = drafts[key].orEmpty()

    @Synchronized fun set(key: String, text: String) { if (text.isEmpty()) drafts.remove(key) else drafts[key] = text }

    @Synchronized fun clear(key: String) { drafts.remove(key) }
}
