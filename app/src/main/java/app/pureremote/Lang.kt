package app.pureremote

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App-Sprache. Wird über AppCompat gespeichert und gilt sofort für die ganze App.
 * Ab Android 13 ist sie auch in den System-Einstellungen unter "App-Sprachen" sichtbar.
 */
object Lang {
    /** Sprach-Tag → Name in der jeweiligen Sprache. Neue Sprachen auch in res/xml/locales_config.xml eintragen. */
    val options = listOf(
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pt" to "Português",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ru" to "Русский",
        "ja" to "日本語",
        "zh-CN" to "简体中文",
    )

    /** "" = Systemsprache */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        val tag = locales[0]?.toLanguageTag() ?: return ""
        return options.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.first
            ?: options.firstOrNull { it.first.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }?.first
            ?: ""
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
