package com.niki914.nexus.agentic.mod.feat.hyper

/**
 * 已安装的小爱指令闸门的纯逻辑判定边界。
 *
 * 这里刻意不引用 Xposed / Android / 宿主类：Hook 只负责反射取字段，取到的都是普通值再交给本对象
 * 判定。这样判定可以用普通 JVM 单测覆盖，而 Hook 与宿主的真实耦合仍只能靠真机日志确认——两层
 * 覆盖范围不同，不能互相代替。
 */
object XiaoaiInstructionGate {

    /** dialog id 的来源。区分“指令自身带 ID”与“回退到 target 的 ID”，两者相关性强度不同。 */
    enum class DialogIdSource {
        /** 指令自身的 header 携带了非空 dialog id。 */
        INSTRUCTION,

        /** 指令没带 ID，回退使用了 response target 的 dialog id。 */
        TARGET_FALLBACK,

        /** 两处都拿不到非空 ID。 */
        NONE,
    }

    enum class Reason {
        /** Nexus 自己注入的指令，原样放回宿主。 */
        INJECTED_BY_NEXUS,

        /** 最终 ASR 结果，属于麦克风/VAD 生命周期，必须放行。 */
        FINAL_ASR,

        /** 用户 query，放行并采集。 */
        TEMPLATE_QUERY,

        /** 拿不到 dialog id，无法关联到任何轮次，按不干预处理。 */
        DIALOG_ID_UNRESOLVED,

        /** 当前 dialog 不属于 Nexus 接管的轮次，交还原生。 */
        DIALOG_NOT_OWNED_BY_INJECTED_TURN,

        /** 命中当前版本配置的白名单，放行。 */
        ALLOWED_INSTRUCTION,

        /** 接管轮次内的其他原生指令，拦截。 */
        NATIVE_INSTRUCTION_BLOCKED,
    }

    sealed interface Decision {
        val reason: Reason

        data class Allow(override val reason: Reason) : Decision
        data class Block(override val reason: Reason) : Decision
    }

    /** 与 [AssistantCapturedInputSource] 对应的采集意图，仅用于让 Hook 知道该走哪条采集路径。 */
    enum class CaptureIntent {
        NONE,
        FINAL_ASR,
        TEMPLATE_QUERY,
    }

    data class Input(
        val fullName: String?,
        val injectedByNexus: Boolean,
        val dialogId: String?,
        val dialogIdSource: DialogIdSource,
        val allowedInstructionFullNames: Set<String>,
        val ownsInjectedTurn: Boolean,
    ) {
        /** 已知采集路径；不在采集分支时为 [CaptureIntent.NONE]。 */
        val captureIntent: CaptureIntent
            get() = when (fullName) {
                FINAL_ASR_FULL_NAME -> CaptureIntent.FINAL_ASR
                TEMPLATE_QUERY_FULL_NAME -> CaptureIntent.TEMPLATE_QUERY
                else -> CaptureIntent.NONE
            }
    }

    fun decide(input: Input): Decision {
        if (input.injectedByNexus) return Decision.Allow(Reason.INJECTED_BY_NEXUS)

        when (input.captureIntent) {
            CaptureIntent.FINAL_ASR -> return Decision.Allow(Reason.FINAL_ASR)
            CaptureIntent.TEMPLATE_QUERY -> return Decision.Allow(Reason.TEMPLATE_QUERY)
            CaptureIntent.NONE -> Unit
        }

        // fullName 读取失败时无法判断指令性质，保持不干预而不是误拦。
        input.fullName ?: return Decision.Allow(Reason.DIALOG_ID_UNRESOLVED)
        if (input.dialogId.isNullOrBlank()) return Decision.Allow(Reason.DIALOG_ID_UNRESOLVED)
        if (!input.ownsInjectedTurn) return Decision.Allow(Reason.DIALOG_NOT_OWNED_BY_INJECTED_TURN)
        if (input.fullName in input.allowedInstructionFullNames) {
            return Decision.Allow(Reason.ALLOWED_INSTRUCTION)
        }
        return Decision.Block(Reason.NATIVE_INSTRUCTION_BLOCKED)
    }

    const val FINAL_ASR_FULL_NAME = "SpeechRecognizer.RecognizeResult"
    const val TEMPLATE_QUERY_FULL_NAME = "Template.Query"
}
