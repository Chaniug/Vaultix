package com.bastion.app.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.bastion.app.data.Language
import java.util.*

object LocaleHelper {
    
    fun setLocale(context: Context, language: Language): Context {
        val locale = when (language) {
            Language.SYSTEM -> getSystemLocale()
            Language.CHINESE -> Locale.CHINA
        }
        
        return updateResources(context, locale)
    }
    
    private fun getSystemLocale(): Locale {
        val systemConfig = android.content.res.Resources.getSystem().configuration
        return systemConfig.locales[0]
    }
    
    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }
    
    fun getCurrentLanguage(context: Context): Language {
        return Language.CHINESE
    }
}