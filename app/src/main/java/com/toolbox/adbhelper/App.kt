package com.toolbox.adbhelper

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("language_tag", "") ?: "")
        )
    }

    companion object {
        lateinit var instance: App
            private set

        fun setLanguage(languageTag: String) {
            instance.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit().putString("language_tag", languageTag).apply()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }
    }
}
