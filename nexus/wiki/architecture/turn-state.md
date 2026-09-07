# Turn State

本文件描述当前源码的轮次身份、接管 ownership 与 runtime admission。

## 宿主轮次

`ConversationTurnState` 包含：

- `turnId`：进程内单调递增；
- `roomId`：宿主 dialog 身份；
- `lastQuery`；
- `mode`：`InjectedLLM` 或 `NativeTakeover`。

`ActiveTurnStore` 只保存当前宿主 owner。所有 XiaoAi 阻断和渲染都必须同时匹配 turnId 与 roomId。异步清理使用 `clearIfOwner(turnId, roomId)`，旧 timeout/final 不能清理后继轮次。

## 输入身份

`AssistantInputIdentity` 为每次捕获生成 requestId：

`hostProcessEpoch : inputSequence : source : turnId : roomLength : roomId`

`source` 区分 `final_asr`、`template_query`、`query_info` 和其他宿主输入。该身份用于跨宿主进程到 `AgentRuntimeService` 的 reserve/commit。

## Admission

`TurnAdmissionGate` 将 reservation 与 active work 分离：

1. `reserve(requestId)` 只预留，不启动 Agent；
2. takeover 决策为 Nexus 后调用 `commit(requestId, turn)`；
3. `AgentRuntimeService` 使用 lazy coroutine，只有 commit 成功才启动；
4. 重复 commit 不会启动第二个 turn；
5. 非 owner、过期 reservation、release 后晚到 commit 均被拒绝；
6. cancel/reset/Binder death/destroy 会清理 gate 和已提交 job。

当前 reservation 有 5 秒有效期。规则解析异常必须 release 并 fail-open；NativeTakeover 也必须 release。

## Reset

通用 reset 会请求 runtime reset、清空当前 owner 与事件上下文。XiaoAi 额外清空输入去重器、按 dialog response-target registry 和渲染 session；Breeno 清理自身 render session。

浮窗 detach + resume grace window 仍是现有宿主 reset 信号。它与长任务生命周期尚未完全解耦，必须在真机验收后再改变，避免任务继续执行但失去可验证输出。

## 关键源码

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ConversationTurnState.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ActiveTurnStore.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AssistantInputIdentity.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/service/TurnAdmissionGate.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/service/AgentRuntimeService.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt`
