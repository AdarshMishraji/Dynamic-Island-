package com.akira.island.core

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalConfiguration
import com.akira.island.data.AppLanguage
import com.akira.island.data.SettingsRepo
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * App and island language. Russian and English are written as a pair right in the code: tr("Зарядка", "Charging").
 * Other languages are tables in assets/i18n/<code>.json: English
 * string → translation. What's missing from the table is shown in English. "As in system" is the phone's language, if it's
 * in the app, otherwise English.
 */
object Lang {
    /** App languages: code → translation table (Russian and English have no tables) */
    val codes = listOf("ru", "en", "id", "pt", "kk", "fr", "es", "ja", "zh")

    private var assets: android.content.res.AssetManager? = null
    private var resources: Resources? = null
    private val tables = HashMap<String, Map<String, String>>()

    /** Translation tables are read from assets; the app calls this at startup. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
        resources = context.resources
    }

    /** Current language code from [codes]. */
    val code: String
        get() = when (val l = SettingsRepo.current.language) {
            AppLanguage.SYSTEM -> systemCode()
            else -> l.code
        }

    /** Not Russian: labels, units and formats are as in English if the language has none of its own */
    val en: Boolean get() = code != "ru"

    /** For dates: "пятница, 24 октября", "Friday, October 24", "Jumat, 24 Oktober" */
    val locale: Locale
        get() = when (code) {
            "ru" -> Locale.forLanguageTag("ru")
            "id" -> Locale.forLanguageTag("id")
            "pt" -> Locale.forLanguageTag("pt-BR")
            "kk" -> Locale.forLanguageTag("kk")
            "fr" -> Locale.FRENCH
            "es" -> Locale.forLanguageTag("es")
            "ja" -> Locale.JAPANESE
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }

    private fun systemCode(): String {
        val lang = runCatching { (resources ?: Resources.getSystem()).configuration.locales[0].language }.getOrNull() ?: Locale.getDefault().language
        // Indonesian on old Android is "in"
        val c = if (lang == "in") "id" else lang
        return if (c in codes) c else "en"
    }

    /** Translation table for language [c]: English string → translation. Empty if there's no file. */
    fun table(c: String): Map<String, String> = synchronized(tables) {
        tables.getOrPut(c) {
            runCatching {
                val raw = assets?.open("i18n/$c.json")?.bufferedReader()?.use { it.readText() } ?: return@runCatching emptyMap()
                Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }.getOrDefault(emptyMap())
        }
    }

    /** String in the current language from a "Russian, English" pair. */
    fun pick(ru: String, en: String): String = when (val c = code) {
        "ru" -> ru
        "en" -> en
        else -> table(c)[en] ?: en
    }
}

/** String in the current language. */
fun tr(ru: String, en: String): String = Lang.pick(ru, en)

/**
 * String with substitutions in the current language: tr("Через {0} минут", "In {0} minutes", n). In translations
 * {0}, {1}… can be reordered; the values will land where needed.
 */
fun trf(ru: String, en: String, vararg args: Any?): String {
    var s = Lang.pick(ru, en)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}

/**
 * Language changed (in settings or system): everything inside is redrawn in the new language.
 * State outside (open tab and screen) stays.
 */
@Composable
fun LangScope(content: @Composable () -> Unit) {
    val language by remember { SettingsRepo.state.map { it.language }.distinctUntilChanged() }.collectAsState(initial = SettingsRepo.current.language)
    // System language changes with the configuration: reading it subscribes to that
    LocalConfiguration.current
    key(language, Lang.code) { content() }
}
