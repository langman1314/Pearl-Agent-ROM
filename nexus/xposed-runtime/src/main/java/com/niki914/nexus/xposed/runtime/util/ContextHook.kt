package com.niki914.nexus.xposed.runtime.util

import android.app.Application
import android.content.Context
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.xposed.api.util.xlog
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Especially for context provider
 */
class ContextHook : Hook {
    override val name: String = "ContextHook"

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val currentApplication = runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication",
            ) as? Application
        }.getOrNull()
        if (currentApplication != null) {
            provide(currentApplication, "ActivityThread.currentApplication")
            return
        }

        lpparam.hookMethod(
            "android.app.Application",
            "attach",
            Context::class.java,
            after = { param ->
                provide(param.thisObject as Application, "Application.attach")
            }
        )
        lpparam.hookMethod(
            "android.app.Instrumentation",
            "callApplicationOnCreate",
            Application::class.java,
            before = { param ->
                (param.args.firstOrNull() as? Application)?.let {
                    provide(it, "Instrumentation.callApplicationOnCreate")
                }
            }
        )
    }

    private fun provide(application: Application, source: String) {
        if (ContextProvider.provide(application)) {
            val message = "[$name] Context successfully provided from $source: ${application.packageName}"
            XposedBridge.log(message)
            xlog(message)
        }
    }
}