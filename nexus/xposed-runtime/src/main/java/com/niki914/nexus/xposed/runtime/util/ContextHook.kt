package com.niki914.nexus.xposed.runtime.util

import android.app.Application
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Especially for context provider
 */
class ContextHook : Hook {
    override val name: String = "ContextHook"

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val currentApplication = AndroidAppHelper.currentApplication()
        if (currentApplication != null) {
            provide(currentApplication, "current application")
            return
        }

        lpparam.hookMethod(
            "android.app.Application",
            "onCreate",
            after = { param ->
                provide(param.thisObject as Application, "Application.onCreate")
            }
        )
    }

    private fun provide(application: Application, source: String) {
        if (ContextProvider.provide(application)) {
            XposedBridge.log(
                "[$name] Context successfully provided from $source: ${application.packageName}"
            )
        }
    }
}