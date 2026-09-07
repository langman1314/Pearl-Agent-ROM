package com.niki914.nexus.agentic.mod.feat.hyper

object FinalAsrPayloadDecoder {
    fun decode(payload: Any): String? {
        return try {
            val isFinal = payload.javaClass.getMethod("isFinal").invoke(payload) as? Boolean
            if (isFinal != true) return null
            val results = payload.javaClass.getMethod("getResults").invoke(payload) as? Iterable<*>
                ?: return null
            results.asSequence()
                .mapNotNull { item ->
                    item ?: return@mapNotNull null
                    (item.javaClass.getMethod("getText").invoke(item) as? String)?.trim()
                }
                .firstOrNull { it.isNotEmpty() }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
