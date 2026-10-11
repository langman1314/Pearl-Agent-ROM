package com.niki914.nexus.agentic.chat.agentic.buildin

/**
 * What a tool exit knows about the *stage* the operation reached.
 *
 * This is the difference between "the call was refused, retry it" and "the call may already have
 * reached the phone". It cannot be recovered from the payload afterwards — by the time a result is
 * JSON, a timeout and a policy refusal look equally like "something went wrong" — so a tool that
 * knows the answer reports it here, and the guard records it instead of guessing.
 *
 * The value is only as good as the production branch that produced it: this type carries the
 * judgement, it does not create one. A payload that *claims* a stage proves nothing, which is why
 * nothing in this file is read from tool output.
 */
enum class DispatchStage {
    /** A production branch proves nothing reached the device: validation, policy, a lookup miss. */
    NOT_DISPATCHED,

    /** The operation reached the device and came back with a real verdict. */
    DISPATCHED_CONFIRMED,

    /** The operation reached the device (or may have) and produced no verdict. */
    DISPATCHED_UNCONFIRMED,
}

/**
 * Which production branch a result came out of.
 *
 * This is the fact the guard cannot recover from the payload: a typed `BuiltinToolResult` envelope,
 * a raw body from a tool wired to the trusted channel, a raw body from a tool that only has the
 * legacy `String` interface, and a custom-command result all look like JSON once they are strings,
 * and "this JSON happens to decode as a canonical envelope" is not evidence about which one ran.
 *
 * The value is assigned by the executor from the branch it really took, never parsed from output.
 * It is an information carrier, not a defence against malicious tool code: code that can choose its
 * own branch can lie about its source, and nothing here pretends otherwise.
 */
enum class ToolResultSource {
    /** Ran through `BuiltinTool.invoke`, so the result is a `BuiltinToolResult` the tool built. */
    TYPED,

    /** A raw tool that answered through [RawOutcomeProducing], so the stage channel is available. */
    RAW_WITH_EVIDENCE,

    /**
     * A raw tool that only implements [RawJsonBuiltinTool]: it hands back a body of its own design
     * and this version has no branch that can say what stage it reached. Its side effects are
     * therefore never released on the strength of that body alone.
     */
    RAW_WITHOUT_EVIDENCE,

    /** A `CustomTool` command run by the custom executor. */
    CUSTOM_COMMAND,
}

/**
 * A tool that can name the operation it is about to attempt, parsed from its own request with the
 * same rules the real dispatch uses.
 *
 * The declared operation is what lets the guard bind a completion credential to the action that is
 * allowed to emit it: a session handle only completes an `open`, an exit code only completes an
 * `exec`. `null` means "no operation could be established" — a request that failed to parse, or a
 * tool whose request carries no such field — and callers then cannot release anything on the
 * strength of the exit's shape alone.
 */
interface DeclaredOperationProducing {
    /** The operation this request asks for, or null when the request does not establish one. */
    fun declaredOperation(argumentsJson: String): String?
}

/**
 * A raw tool result together with the stage its own exit can prove.
 *
 * [operation] names the action actually performed (`open`, `exec`, `write`, ...), so a completion
 * credential can be bound to the operation that is allowed to emit it rather than to a key name.
 */
data class RawToolOutcome(
    val resultJson: String,
    val stage: DispatchStage,
    val operation: String,
)

/**
 * Implemented by raw tools whose exits can prove their stage.
 *
 * This is deliberately *additive*: [RawJsonBuiltinTool.invokeRawJson] keeps its `String` signature,
 * so existing tools and existing tests keep compiling, and a tool opts in only when one of its real
 * branches actually knows the answer. A tool that does not implement this reports no stage, and the
 * guard then treats its side effects as unconfirmed rather than assuming the best.
 *
 * Implementations must answer with the result of a *single* execution — the caller calls this once
 * and uses both halves, so a tool must not obtain the evidence by running the work twice.
 */
interface RawOutcomeProducing {
    suspend fun invokeRawJsonWithEvidence(request: BuiltinToolRequest): RawToolOutcome
}

/** The operation name used when a tool rejected the request before it could identify one. */
const val UNKNOWN_OPERATION = "unknown"

/** Pairs a tool exit's JSON with the stage that exit really proves. */
fun rawOutcome(operation: String, stage: DispatchStage, json: String): RawToolOutcome =
    RawToolOutcome(resultJson = json, stage = stage, operation = operation)

/**
 * The result of running one tool, plus the stage evidence its exit could prove.
 *
 * [stage] and [operation] are null when the tool reported no evidence, which is the case for every
 * tool that has not been audited to have a branch that knows the answer. Callers must read "no
 * evidence" as *unproven*, never as "nothing happened".
 *
 * [source] records which branch really produced the body. Unlike [stage] it is always known — every
 * exit comes from somewhere — so callers can tell "this tool has no stage channel" apart from "this
 * tool has one and it reported nothing", and neither is read as permission to release.
 */
data class ToolExecution(
    val resultJson: String,
    val stage: DispatchStage? = null,
    val operation: String? = null,
    val source: ToolResultSource? = null,
)
