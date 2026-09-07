package com.niki914.nexus.agentic.mod.feat.hyper

/**
 * Recognizes XiaoAi's internal LLM stream-card container without allowing arbitrary
 * Template.FrontendPage navigation through the takeover instruction gate.
 */
object XiaoaiStreamContainerInstruction {
    data class Shape(
        val loadType: String,
        val paramType: String,
        val loadUrlPresent: Boolean,
        val loadUrlMatchesMarker: Boolean,
        val loadHtmlPresent: Boolean,
        val cardTypePresent: Boolean,
        val instructionsPresent: Boolean,
    )

    fun inspectShape(instruction: Any, loadUrlMarker: String): Shape? {
        return try {
            val payload = instruction.javaClass.getMethod("getPayload").invoke(instruction) ?: return null
            val loadUrl = payload.javaClass.getMethod("getLoadUrl").invoke(payload) ?: return null
            val loadUrlPresent = optionalPresent(loadUrl)
            val loadUrlValue = if (loadUrlPresent) optionalString(loadUrl) else null
            Shape(
                loadType = payload.javaClass.getMethod("getLoadType").invoke(payload)?.toString().orEmpty(),
                paramType = payload.javaClass.getMethod("getParamType").invoke(payload)?.toString().orEmpty(),
                loadUrlPresent = loadUrlPresent,
                loadUrlMatchesMarker = !loadUrlValue.isNullOrEmpty() && loadUrlValue.contains(loadUrlMarker),
                loadHtmlPresent = optionalPresent(payload.javaClass.getMethod("getLoadHtml").invoke(payload)),
                cardTypePresent = optionalPresent(payload.javaClass.getMethod("getCardType").invoke(payload)),
                instructionsPresent = optionalPresent(payload.javaClass.getMethod("getInstructions").invoke(payload)),
            )
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun isSafeContainer(
        instruction: Any,
        fullName: String?,
        expectedFullName: String,
        loadUrlMarker: String,
    ): Boolean {
        if (fullName != expectedFullName || loadUrlMarker.isBlank()) return false
        return try {
            val payload = instruction.javaClass.getMethod("getPayload").invoke(instruction) ?: return false
            val loadUrl = payload.javaClass.getMethod("getLoadUrl").invoke(payload) ?: return false
            val present = loadUrl.javaClass.getMethod("isPresent").invoke(loadUrl) as? Boolean
            if (present != true) return false
            val value = loadUrl.javaClass.getMethod("get").invoke(loadUrl) as? String ?: return false
            value.contains(loadUrlMarker)
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun optionalPresent(value: Any?): Boolean =
        value?.javaClass?.getMethod("isPresent")?.invoke(value) as? Boolean == true

    private fun optionalString(value: Any): String? =
        value.javaClass.getMethod("get").invoke(value) as? String
}
