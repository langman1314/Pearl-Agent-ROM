package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.xposed.api.util.xTry
import com.niki914.nexus.xposed.api.util.xlog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小爱原生固定文本播报适配器。
 *
 * 默认关闭，只有显式测试入口能触发。它只播报编译期常量 [XiaoaiFixedTextGate.FIXED_TEST_TEXT]：
 * 测试文本刻意不放进配置，也不接受调用方传入，因此这条通道在结构上无法被 Agent 动态回答借用，
 * 也不会触发任何手机工具。
 *
 * 为什么走 `dh0.u.speakTts(String, kz.b)` 而不是注入 `SpeechSynthesizer.SpeakStream` 指令：
 * 对已核对 sha256 的该版本 APK（507012002）反编译后可见，`SpeakStream` 的消费方（`cb0.db`、
 * `pb0.s`）只是把 payload 文本累加进 `SpeakContentManager`
 * （`com.xiaomi.voiceassistant.instruction.utils.b2`），并不会启动合成或播放；真正发声要经过
 * `TTSPlayView.onPlayClick()` → `ea0.n1.speakTts(text)` 或等价的 `dh0.u.speakTts` 静态入口
 * （其内部走 `r00.g.speak(...)`）。只注入 `SpeakStream` 会是一个“看着接通、其实没有声音”的占位
 * 实现，因此这里不使用该路径。
 *
 * 本类只负责发起调用并记录证据。**它不能证明设备真的发出了声音**——是否有声音只能由真机听感确认，
 * 日志只能证明代码跑到了这里。
 */
object XiaoaiFixedTextBroadcast {

    private const val NAME = "XiaoaiFixedTextBroadcast"

    /** 同一次测试只允许一个播报在途，杜绝自动重放。 */
    private val inFlight = AtomicBoolean(false)

    /** 已绑定的一次测试。绑定后目标失效或会话结束都必须立即失败，而不是重试。 */
    data class Binding(
        val turnId: Long,
        val dialogId: String,
        val hostVersionCode: Long,
        val classLoader: ClassLoader,
    )

    /**
     * 发起一次固定文本播报。[BroadcastOutcome.Dispatched] 只表示反射调用已执行，**不代表已发声**。
     */
    fun broadcastFixedText(
        hostClassLoader: ClassLoader?,
        hostVersionCode: Long,
    ): BroadcastOutcome {
        val config = xTry("$NAME.config") { XiaoaiConfigProvider.FixedTextBroadcast }
        val binding = resolveBinding(hostClassLoader, hostVersionCode)

        val decision = XiaoaiFixedTextGate.decide(
            XiaoaiFixedTextGate.Input(
                enabled = xTry("$NAME.enabled") { config?.enabled } ?: false,
                hostVersionCode = hostVersionCode,
                text = XiaoaiFixedTextGate.FIXED_TEST_TEXT,
                sessionBound = binding != null,
                // 目标有效性单独判定：宿主类加载器能解析出发声入口才算目标可用。
                // 与“会话是否绑定”是两个不同的失败原因，混在一起会让日志无法区分版本漂移与会话缺失。
                targetValid = config != null && resolveSpeakEntry(config, hostClassLoader) != null,
                inFlight = inFlight.get(),
            )
        )
        if (decision is XiaoaiFixedTextGate.Decision.Deny) {
            xlog("[$NAME] fixed_text_denied reason=${decision.reason}")
            return BroadcastOutcome.Denied(decision.reason)
        }
        if (!inFlight.compareAndSet(false, true)) {
            xlog("[$NAME] fixed_text_denied reason=${XiaoaiFixedTextGate.Reason.ALREADY_IN_FLIGHT}")
            return BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.ALREADY_IN_FLIGHT)
        }
        return try {
            // 走到这里 binding 与 config 必非空（Allow 要求 sessionBound 与 targetValid），
            // 仍保留空值分支以防后续改动破坏该不变量，且释放必须走 finally。
            if (binding == null || config == null) {
                BroadcastOutcome.Denied(XiaoaiFixedTextGate.Reason.SESSION_NOT_BOUND)
            } else {
                dispatch(binding, config)
            }
        } finally {
            // 任何路径都必须释放占位，否则一次失败会永久堵死后续测试。
            inFlight.set(false)
        }
    }

    /**
     * 绑定当前会话与目标。会话不存在、dialog 为空或宿主类加载器缺失都视为未绑定，
     * 交由闸门按 fail-fast 拒绝，而不是猜一个默认目标。
     */
    private fun resolveBinding(hostClassLoader: ClassLoader?, hostVersionCode: Long): Binding? {
        val classLoader = hostClassLoader ?: return null
        val state = ActiveTurnStore.getCurrent() ?: return null
        if (state.roomId.isBlank()) return null
        return Binding(
            turnId = state.turnId,
            dialogId = state.roomId,
            hostVersionCode = hostVersionCode,
            classLoader = classLoader,
        )
    }

    /**
     * 解析宿主发声入口。解析不到就说明宿主版本与配置不匹配，此时必须立即失败，
     * 而不是退化成别的调用路径——那会在未知宿主上产生无法预期的副作用。
     */
    private fun resolveSpeakEntry(
        config: XiaoaiConfigProvider.FixedTextBroadcast,
        classLoader: ClassLoader?,
    ): java.lang.reflect.Method? {
        val loader = classLoader ?: return null
        return xTry("$NAME.resolveSpeakEntry") {
            val clazz = Class.forName(config.speakEntryClass, false, loader)
            val paramTypes = config.speakEntryParamTypes
                .map { Class.forName(it, false, loader) }
                .toTypedArray()
            clazz.getMethod(config.speakEntryMethod, *paramTypes)
        }
    }

    private fun dispatch(
        binding: Binding,
        config: XiaoaiConfigProvider.FixedTextBroadcast,
    ): BroadcastOutcome {
        val entry = xTry("$NAME.dispatch") {
            val method = resolveSpeakEntry(config, binding.classLoader)
                ?: error("speak entry unresolved")
            // 回调传 null：该版本自身的调用点（dh0.b1、dh0.e 等）也传 null，未猜测构造参数。
            method.invoke(null, XiaoaiFixedTextGate.FIXED_TEST_TEXT, null)
            true
        } ?: false
        return finishDispatch(entry, binding, config)
    }

    private fun finishDispatch(
        entry: Boolean,
        binding: Binding,
        config: XiaoaiConfigProvider.FixedTextBroadcast,
    ): BroadcastOutcome {
        if (!entry) {
            xlog(
                "[$NAME] fixed_text_dispatch_failed versionCode=${binding.hostVersionCode} " +
                    "entry=${config.speakEntryClass}.${config.speakEntryMethod} dialogHash=${binding.dialogId.hashCode()}"
            )
            return BroadcastOutcome.DispatchFailed
        }

        xlog(
            "[$NAME] fixed_text_dispatched versionCode=${binding.hostVersionCode} " +
                "entry=${config.speakEntryClass}.${config.speakEntryMethod} " +
                "textLength=${XiaoaiFixedTextGate.FIXED_TEST_TEXT.length} " +
                "dialogHash=${binding.dialogId.hashCode()} turnId=${binding.turnId} " +
                "note=dispatch_only_not_audible_proof"
        )
        return BroadcastOutcome.Dispatched
    }

    /**
     * 取消当前原生播报。调用宿主自身的停止入口，不吞异常也不重放。
     * classLoader 为 null（宿主未挂载）时直接失败，而不是回退到错误的目标。
     */
    fun cancel(classLoader: ClassLoader?, hostVersionCode: Long): Boolean {
        if (classLoader == null) {
            xlog("[$NAME] fixed_text_cancel stopped=false reason=no_classloader")
            return false
        }
        val config = xTry("$NAME.cancel.config") { XiaoaiConfigProvider.FixedTextBroadcast }
        val stopped = if (config == null) {
            false
        } else {
            xTry("$NAME.cancel") {
                val clazz = Class.forName(config.stopEntryClass, false, classLoader)
                val instance = clazz.getMethod("getInstance").invoke(null)
                clazz.getMethod(config.stopEntryMethod).invoke(instance)
                true
            } ?: false
        }
        xlog("[$NAME] fixed_text_cancel stopped=$stopped versionCode=$hostVersionCode")
        return stopped
    }

    /** 仅供测试重置。 */
    fun resetForTest() {
        inFlight.set(false)
    }

    sealed interface BroadcastOutcome {
        /** 反射调用已发起，仍需真机确认是否发声。 */
        data object Dispatched : BroadcastOutcome

        /** 前置条件不满足，未发起调用。 */
        data class Denied(val reason: XiaoaiFixedTextGate.Reason) : BroadcastOutcome

        /** 前置条件满足但反射调用失败，通常意味着宿主版本与配置不匹配。 */
        data object DispatchFailed : BroadcastOutcome
    }
}
