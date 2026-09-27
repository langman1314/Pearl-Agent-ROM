package com.niki914.nexus.agentic.mod.feat.hyper

/**
 * 原生固定文本播报的纯判定边界，不引用 Android / Xposed / 宿主类，因此可用普通 JVM 单测覆盖。
 *
 * 该适配器默认关闭，且只接受**固定常量**文本：它不是 Agent 动态回答的播报通道，也不能触发任何
 * 手机工具。判定为 ALLOWED 只表示“反射调用可以发起”，不代表设备真的发出了声音——是否有声音只能
 * 由真机听感确认。
 */
object XiaoaiFixedTextGate {

    /** 唯一通过签名与哈希核对的小爱版本。哈希见 507012002/config.json 的 apk_sha256。 */
    const val VERIFIED_HOST_VERSION_CODE = 507012002L

    /**
     * 固定测试文本。刻意写成编译期常量而不是从 Agent 回答传入，保证该入口永远无法被动态文本借用。
     */
    const val FIXED_TEST_TEXT = "Nexus 原生播报测试"

    enum class Reason {
        /** 配置未显式开启，默认关闭。 */
        DISABLED,

        /** 宿主版本不是已核对签名的版本，立即失败而不是猜测行为。 */
        VERSION_MISMATCH,

        /** 文本不是固定常量，拒绝（防止该入口被当作 Agent 动态文本通道）。 */
        TEXT_NOT_FIXED_CONSTANT,

        /** 同一会话已有播报在途，拒绝重入（禁止自动重放）。 */
        ALREADY_IN_FLIGHT,

        /** 未绑定当前会话/目标，生命周期未知。 */
        SESSION_NOT_BOUND,

        /** 绑定过的目标已失效。 */
        TARGET_INVALIDATED,

        /** 全部前置条件满足，可以发起一次播报调用。 */
        ALLOWED,
    }

    data class Input(
        val enabled: Boolean,
        val hostVersionCode: Long,
        val text: String,
        val sessionBound: Boolean,
        val targetValid: Boolean,
        val inFlight: Boolean,
    )

    sealed interface Decision {
        val reason: Reason

        data class Allow(override val reason: Reason) : Decision

        /** 拒绝时携带原因，调用方必须立即放弃本次播报而不是继续尝试。 */
        data class Deny(override val reason: Reason) : Decision
    }

    fun decide(input: Input): Decision {
        if (!input.enabled) return Decision.Deny(Reason.DISABLED)
        if (input.hostVersionCode != VERIFIED_HOST_VERSION_CODE) {
            return Decision.Deny(Reason.VERSION_MISMATCH)
        }
        // 先校验文本：即使会话状态异常，也不允许非固定文本经此入口流出。
        if (input.text != FIXED_TEST_TEXT) return Decision.Deny(Reason.TEXT_NOT_FIXED_CONSTANT)
        if (input.inFlight) return Decision.Deny(Reason.ALREADY_IN_FLIGHT)
        if (!input.sessionBound) return Decision.Deny(Reason.SESSION_NOT_BOUND)
        if (!input.targetValid) return Decision.Deny(Reason.TARGET_INVALIDATED)
        return Decision.Allow(Reason.ALLOWED)
    }
}
