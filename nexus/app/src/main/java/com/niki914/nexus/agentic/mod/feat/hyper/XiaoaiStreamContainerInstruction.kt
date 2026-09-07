package com.niki914.nexus.agentic.mod.feat.hyper

/**
 * Recognizes XiaoAi's internal LLM stream-card container without allowing arbitrary
 * Template.FrontendPage navigation through the takeover instruction gate.
 */
object XiaoaiStreamContainerInstruction {
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
}
