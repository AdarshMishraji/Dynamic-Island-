package com.akira.island.service

/** This app's own recent log lines, for a bug report. Android lets an app read only its own log. */
object DevLog {
    fun dump(maxLines: Int = 4000): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", maxLines.toString(), "-v", "threadtime").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
    }.getOrDefault("")
}
