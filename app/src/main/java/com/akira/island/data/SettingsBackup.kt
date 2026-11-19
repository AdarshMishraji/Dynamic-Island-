package com.akira.island.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** All of the user's settings in one JSON file, to move to another phone or come back to after a reset. */
object SettingsBackup {
    private const val FORMAT = "dynamic-island-settings"
    private const val VERSION = 1

    /** A backup is a few KB; a far bigger file is not one of ours. */
    const val MAX_BYTES = 512 * 1024

    private val pretty = Json { prettyPrint = true }

    /** The text of [input], or null when it is bigger than any backup can be. */
    fun readLimited(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun export(): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", JsonPrimitive(FORMAT))
        put("version", JsonPrimitive(VERSION))
        put("settings", Json.parseToJsonElement(SettingsRepo.exportJson()))
        put("extras", Json.parseToJsonElement(ExtrasRepo.exportJson()))
    })

    /** True when [raw] was one of our backups and has been applied. Nothing changes when it was not. */
    fun import(raw: String): Boolean = runCatching {
        require(raw.length <= MAX_BYTES)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT && (root["version"]?.jsonPrimitive?.int ?: 0) <= VERSION)
        val settings = root["settings"]?.toString() ?: error("no settings")
        val extras = root["extras"]?.toString()
        // Decode both before applying either, so a damaged file leaves everything as it was
        require(SettingsRepo.importJson(settings, dryRun = true))
        require(extras == null || ExtrasRepo.importJson(extras, dryRun = true))
        SettingsRepo.importJson(settings)
        if (extras != null) ExtrasRepo.importJson(extras)
        true
    }.getOrDefault(false)
}
