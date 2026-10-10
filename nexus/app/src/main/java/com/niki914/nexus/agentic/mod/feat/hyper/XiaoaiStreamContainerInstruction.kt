package com.niki914.nexus.agentic.mod.feat.hyper

/**
 * 对 `Template.FrontendPage` payload 的只读、脱敏描述。
 *
 * shape 的唯一用途是让接管闸门的拦截决策在真机上可观测。它只暴露布尔量、schema 枚举名和内层
 * 指令**数量**，绝不包含 load URL、HTML 正文、卡片文本或任何其他 payload 内容，因此日志里不会
 * 出现用户数据。
 *
 * 这里刻意不提供 `isSafeContainer` 之类的放行判定：宿主自身的
 * `TemplateReactNativeCard.identifyBundle()` 只把 `contains("stream.bundle")` 当作卡片分类标志，
 * 该子串无法证明容器内层指令没有副作用，所以已安装的闸门选择拦截容器，而不是放行。
 */
object XiaoaiStreamContainerInstruction {

    /** 内层指令数量未知（payload 未提供，或提供了但结构不是集合）。 */
    const val INNER_INSTRUCTION_COUNT_UNKNOWN = -1

    data class Shape(
        val loadType: String,
        val paramType: String,
        val loadUrlPresent: Boolean,
        val loadUrlMatchesMarker: Boolean,
        val loadHtmlPresent: Boolean,
        val cardTypePresent: Boolean,
        val innerInstructionCount: Int,
    ) {
        /**
         * 内层指令是否可能带来副作用。只有“确认不存在”才返回 false；数量未知时按保守方向
         * 视为可能存在。
         */
        val innerInstructionsPossiblyPresent: Boolean
            get() = innerInstructionCount != 0
    }

    /**
     * 读取脱敏 shape。结构性读取失败时返回 null，调用方据此继续按“无法证明安全”处理。
     */
    fun inspectShape(instruction: Any?, loadUrlMarker: String): Shape? {
        if (instruction == null) return null
        return try {
            val payload = instruction.javaClass.getMethod("getPayload").invoke(instruction) ?: return null
            val loadUrl = payload.javaClass.getMethod("getLoadUrl").invoke(payload)
            val loadUrlPresent = optionalPresent(loadUrl)
            val loadUrlValue = if (loadUrlPresent) optionalValue(loadUrl) as? String else null
            Shape(
                loadType = payload.javaClass.getMethod("getLoadType").invoke(payload)?.toString().orEmpty(),
                paramType = payload.javaClass.getMethod("getParamType").invoke(payload)?.toString().orEmpty(),
                loadUrlPresent = loadUrlPresent,
                loadUrlMatchesMarker = !loadUrlMarker.isBlank() &&
                    !loadUrlValue.isNullOrEmpty() &&
                    loadUrlValue.contains(loadUrlMarker),
                loadHtmlPresent = optionalPresent(payload.javaClass.getMethod("getLoadHtml").invoke(payload)),
                cardTypePresent = optionalPresent(payload.javaClass.getMethod("getCardType").invoke(payload)),
                innerInstructionCount = innerInstructionCount(
                    payload.javaClass.getMethod("getInstructions").invoke(payload)
                ),
            )
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun innerInstructionCount(instructions: Any?): Int {
        if (!optionalPresent(instructions)) return 0
        val value = optionalValue(instructions) ?: return 0
        return when (value) {
            is Collection<*> -> value.size
            is Array<*> -> value.size
            else -> INNER_INSTRUCTION_COUNT_UNKNOWN
        }
    }

    private fun optionalPresent(value: Any?): Boolean =
        value?.javaClass?.getMethod("isPresent")?.invoke(value) as? Boolean == true

    private fun optionalValue(value: Any?): Any? =
        value?.javaClass?.getMethod("get")?.invoke(value)
}
