package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult

/**
 * Decides whether a UI control must be confirmed manually by the user before Nexus taps it.
 *
 * Matching is deliberately *bounded* instead of a global substring search. A global `contains`
 * check both misses real commit controls (the label `确认支付` does not contain the standalone
 * keyword `支付`) and over-blocks plain navigation (`支付方式` / `发送记录` merely contain the
 * characters of a commit verb).
 *
 * English labels keep word-boundary semantics: a keyword only counts as a whole token, so
 * `Send message` is blocked while `Payment methods` and `Messages` are not.
 *
 * Chinese labels are matched as bounded `[modifier] verb [object]` commit phrases. A noun-forming
 * tail (`方式`, `记录`, `历史`, `列表`, ...) turns the label into a navigation/lookup affordance and is
 * allowed, except when the verb itself is destructive (`删除`, `移除`, `清空`, `擦除`) - deleting
 * records must still be confirmed.
 */
object SensitiveUiActionPolicy {
    /** English keywords, matched on token boundaries. Multi-word phrases are matched verbatim. */
    private val englishFinalEffectKeywords = setOf(
        "send", "post", "publish", "delete", "remove", "erase",
        "pay", "buy", "purchase", "place order", "confirm order", "submit order",
        "transfer", "wire", "call", "dial", "grant", "allow",
    )

    /** Precompiled whole-token patterns, so `pay` never matches `payment`. */
    private val englishTokenPatterns = englishFinalEffectKeywords.map { keyword ->
        Regex("(?<![a-z0-9])" + Regex.escape(keyword) + "(?![a-z0-9])")
    }

    /** Chinese commit verbs. The label must start with one (after bounded modifiers). */
    private val chineseFinalEffectVerbs = setOf(
        "发送", "发出", "发布", "提交订单", "确认订单", "提交",
        "删除", "移除", "清空", "擦除",
        "支付", "付款", "购买", "下单", "转账", "汇款",
        "拨打", "呼叫", "允许", "授权",
    )

    /** Verbs whose effect stays destructive even when followed by a noun-forming tail. */
    private val chineseDestructiveVerbs = setOf("删除", "移除", "清空", "擦除")

    /** Bounded modifiers that may precede a commit verb, e.g. `确认支付`, `立即发送`. */
    private val chineseModifiers = setOf(
        "确认", "确定", "立即", "马上", "立刻", "现在", "直接",
        "一键", "再次", "继续", "请", "是否",
    )

    /** Bounded objects that may follow a commit verb, e.g. `拨打电话`, `发送消息`. */
    private val chineseObjectNouns = setOf(
        "消息", "内容", "邮件", "评论", "动态", "订单", "账单", "款项",
        "文件", "照片", "图片", "视频", "号码", "电话", "联系人", "好友",
        "申请", "请求", "权限", "验证码", "位置", "全部",
    )

    /** Noun-forming tails that make a label a lookup/navigation affordance, not a commit. */
    private val chineseNavigationTails = setOf(
        "方式", "记录", "历史", "列表", "设置", "管理", "详情", "说明",
        "信息", "时间", "日志", "状态",
    )

    private val whitespace = Regex("\\s+")

    /** Edge separators stripped before matching, so `确认支付 »` still matches. */
    private const val EDGE_SEPARATORS = " \t\r\n:：;；,，.。!！?？、|/\\-—…·»›)]}）】」》"

    /**
     * Returns true when at least one of the candidate labels (usually the node text and its
     * content description) describes a final-effect action that must be confirmed by the user.
     */
    fun requiresManualConfirmation(vararg labels: CharSequence?): Boolean {
        return labels.asSequence()
            .mapNotNull { it?.toString() }
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .any(::labelRequiresConfirmation)
    }

    /**
     * The gate a tapping action must pass before anything is dispatched.
     *
     * Returns null when the action may proceed, or the refusal to hand back when the control looks
     * like a final effect that only the user may trigger.
     *
     * Only actions that actually commit something are gated: scrolling and text entry cannot send,
     * order, pay, or delete, and refusing them would just break ordinary navigation and typing.
     */
    fun clickGate(
        action: NodeAction,
        text: CharSequence?,
        contentDescription: CharSequence?,
    ): BuiltinToolResult? {
        if (action != NodeAction.CLICK && action != NodeAction.LONG_CLICK) return null
        if (!requiresManualConfirmation(text, contentDescription)) return null
        return BuiltinToolResult.failure(
            code = "SENSITIVE_ACTION_CONFIRMATION_REQUIRED",
            message = "This final action may send, publish, delete, order, pay, transfer, call, " +
                "or grant access. Nexus may prepare the workflow, but the user must confirm this " +
                "control manually.",
        )
    }

    private fun normalize(raw: String): String {
        return raw.lowercase()
            .replace(whitespace, " ")
            .trim(*EDGE_SEPARATORS.toCharArray())
    }

    private fun labelRequiresConfirmation(label: String): Boolean {
        if (englishTokenPatterns.any { it.containsMatchIn(label) }) return true

        // The bare label first, so a commit phrase that is itself a verb (`确认订单`) still matches.
        commitPhrase(label)?.let { (verb, tail) -> if (tailCommits(verb, tail)) return true }

        // Then with leading modifiers stripped, so `确认支付` / `立即发送` match too.
        val stripped = label.stripModifierPrefixes()
        if (stripped != label) {
            commitPhrase(stripped)?.let { (verb, tail) ->
                if (tailCommits(verb, tail)) return true
            }
        }
        return false
    }

    /** Splits `拨打电话` into verb `拨打` + tail `电话`; the longest verb wins so `提交订单` beats `提交`. */
    private fun commitPhrase(body: String): Pair<String, String>? {
        if (body.isEmpty()) return null
        val verb = chineseFinalEffectVerbs
            .filter { body.startsWith(it) }
            .maxByOrNull { it.length }
            ?: return null
        return verb to body.removePrefix(verb)
    }

    private fun tailCommits(verb: String, tail: String): Boolean {
        if (tail.isEmpty()) return true
        if (tail in chineseObjectNouns) return true
        if (tail in chineseNavigationTails) return verb in chineseDestructiveVerbs
        return false
    }

    private fun String.stripModifierPrefixes(): String {
        var result = this
        while (true) {
            val modifier = chineseModifiers.firstOrNull { result.startsWith(it) } ?: return result
            result = result.removePrefix(modifier).trimStart()
        }
    }
}
