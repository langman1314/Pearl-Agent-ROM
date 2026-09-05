package a0.a0.a0.a0.a0.a0

import com.niki914.nexus.agentic.app.getInstalledPackageVersion
import com.niki914.nexus.agentic.mod.HookLocalSettings
import com.niki914.nexus.agentic.mod.XService
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiChatHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoChatHook
import com.niki914.nexus.agentic.repo.ApkIdentityVerifier
import com.niki914.nexus.agentic.repo.WebSettingsFailureReason
import com.niki914.nexus.agentic.repo.WebSettingsResult
import com.niki914.nexus.agentic.repo.canInstallHooks
import com.niki914.nexus.agentic.repo.XIpcDomainSettingsStore
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.client.AgentRuntimeClient
import com.niki914.nexus.store.HostApp
import com.niki914.nexus.store.XValues
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.xposed.runtime.IXposed
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import com.niki914.nexus.xposed.runtime.core.runtime.Runtime
import com.niki914.nexus.xposed.runtime.core.runtime.RuntimeBootstrap
import com.niki914.nexus.xposed.runtime.util.ContextHook
import com.niki914.nexus.xposed.runtime.util.HookSideLoader
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 仅在锁屏时生效
class Entrance : IXposed() {
    companion object {
        private val scope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    }

    override fun getTarget() =
        Target.filter(*XValues.appList.toTypedArray())

    override fun onLoad(params: XC_LoadPackage.LoadPackageParam) {
        HookSideLoader.load(scope, ContextHook(), params)
//        HookSideLoader.load(scope, ActivityHook(), params)
//        HookSideLoader.load(scope, FloatWindowHook(), params)
        scope.launch(Dispatchers.IO) {
            val ctx = ContextProvider.await()
            val client = AgentRuntimeClient(ctx)
            if (!client.connectAndAwait()) {
                com.niki914.nexus.xposed.api.util.xtlog(
                    "NexusRuntime",
                    "runtime service unavailable; leaving native assistant untouched",
                )
                return@launch
            }
            XRepo.init(ctx, XIpcDomainSettingsStore(client))

            HookLocalSettings.update(ctx, client)
            val webSettingsResult = XRepo.web.await()
            val targetPkg = params.packageName
            val exactConfigAvailable = webSettingsResult.canInstallHooks()
            val requiresApkIdentity = targetPkg == HostApp.XiaoAi.packageName
            val apkIdentityMatches = !requiresApkIdentity ||
                (webSettingsResult as? WebSettingsResult.Success)
                    ?.takeIf { exactConfigAvailable }
                    ?.let { result ->
                        ApkIdentityVerifier.matches(
                            expectedSha256 = result.settings.apkSha256,
                            sourcePath = params.appInfo?.sourceDir,
                        )
                    } == true
            val isApkIdentityMismatch = exactConfigAvailable && !apkIdentityMatches
            val isFallbackVersion =
                webSettingsResult is WebSettingsResult.Success && webSettingsResult.isFallbackVersion
            val isNetworkError =
                webSettingsResult is WebSettingsResult.RequestFailed &&
                        webSettingsResult.reason == WebSettingsFailureReason.NetworkUnavailable
            val isNoSupportedVersion =
                isFallbackVersion || (
                        webSettingsResult is WebSettingsResult.RequestFailed &&
                                webSettingsResult.reason in setOf(
                                    WebSettingsFailureReason.ServerError,
                                    WebSettingsFailureReason.UnsupportedVersion,
                                )
                        )

            when {
                isNetworkError -> {
                    XService.postNetworkErrorNotification(client)
                }

                isNoSupportedVersion || isApkIdentityMismatch -> {
                    XService.postUnsupportedVersionNotification(
                        hostApp = HostApp.fromPackageName(targetPkg),
                        hostVersion = targetPkg?.let {
                            ctx.getInstalledPackageVersion(it)?.versionName
                        },
                        client = client,
                    )
                }
            }

            if (exactConfigAvailable && apkIdentityMatches) {
                onSettingsFetched(params, targetPkg, client)
            }
        }
    }

    private fun onSettingsFetched(
        params: XC_LoadPackage.LoadPackageParam,
        targetPkg: String?,
        client: AgentRuntimeClient
    ) {
        // 根据 targetPkg 进行映射和 Hook 路由
        val hostApp = HostApp.fromPackageName(params.packageName)
        val hookInstance: Hook? = when {
            targetPkg != params.packageName -> null
            hostApp == HostApp.Breeno -> BreenoChatHook(scope, client)
            hostApp == HostApp.XiaoAi -> XiaoaiChatHook(scope, client)
            else -> null
        }

        hookInstance ?: return

        RuntimeBootstrap.installIfNeeded(
            params,
            create = {
                Runtime(
                    scope = scope,
                    hooks = listOf(
                        hookInstance
                    )
                )
            }
        )
    }
}
