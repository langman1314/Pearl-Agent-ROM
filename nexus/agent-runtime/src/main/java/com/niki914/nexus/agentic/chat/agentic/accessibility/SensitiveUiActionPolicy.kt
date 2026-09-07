package com.niki914.nexus.agentic.chat.agentic.accessibility

object SensitiveUiActionPolicy {
    private val finalEffectLabels = setOf(
        "send", "post", "publish", "delete", "remove", "erase",
        "pay", "buy", "purchase", "place order", "confirm order", "submit order",
        "transfer", "wire", "call", "dial", "grant", "allow",
        "发送", "发出", "发布", "删除", "移除", "清空",
        "支付", "付款", "购买", "下单", "提交订单", "确认订单",
        "转账", "汇款", "拨打", "呼叫", "允许", "授权",
    )

    fun requiresManualConfirmation(vararg labels: CharSequence?): Boolean {
        return labels.asSequence()
            .mapNotNull { it?.toString()?.trim()?.lowercase() }
            .filter { it.isNotEmpty() }
            .any { label ->
                label in finalEffectLabels || finalEffectLabels.any { keyword ->
                    label.startsWith("$keyword ") || label.endsWith(" $keyword")
                }
            }
    }
}
