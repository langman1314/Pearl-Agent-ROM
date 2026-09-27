# Render Pipeline

本文件只描述 `TurnMode.InjectedLLM` 轮次下的响应注入链路。`TurnMode.NativeTakeover` 会在 `AbstractAssistantHook.handleCapturedQuery(...)` 提前返回，保留宿主原生回答路径，不进入这里的渲染注入流程。

## 共用前置条件

- 两个宿主都会先安装 response hooks，但实际是否拦截原生输出、是否消费 `LLMController.stream(query)`，取决于当前活跃轮次是否已经被写成 `InjectedLLM`。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt` 会先完成 takeover 路由，再决定是否继续走 `dispatchQueryToLLM(...)`。
- 因此，“takeover 路由”是“render injection”之前的前置条件，不是注入链中的一个附属步骤。

## Breeno 渲染管线

### 注入模型

Breeno 走回答卡片层的**单卡片全量刷新**：

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt` 的 `dispatchQueryToLLM()` 直接消费 `LLMController.stream(query)`，并把累计文本交给 `renderStreamCard(...)`。
- 首帧创建 mock bean 后通过 `dataCenter.insertMessage()` 插入回答卡片。
- 后续分片和终帧通过 `dataCenter.updateMessage()` 持续刷新同一张卡片。
- `chunk` 传入的是累计文本，不是 delta。

### 前置捕获与原生阻断

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/CaptureInputHook.kt` 在宿主输入链路里捕获 query 与 roomId，同时缓存 `DataCenter` 实例。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/BlockNativeCardHook.kt` 只在 `InjectedLLM` 模式下拦截原生回答卡片；`NativeTakeover` 或无活跃 turn 时直接放行。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/SuppressCleanupHook.kt` 也只在 `InjectedLLM` 模式下把命中的清理操作替换为 `DoNothingOperation`，避免宿主把注入卡片清掉。

### 生命周期

- `BreenoChatHook` 用 `Mutex + currentRenderSession` 维持单个活跃渲染会话。
- `onTurnStateChanged(...)` 在 `NativeTakeover` 轮次会立即清空当前 render session，避免上一轮注入状态残留。
- `onSessionReset()` 会同时重置 `LLMController` 与当前 render session。
- 终帧会补做一次卡片刷新并恢复反馈区显示，然后清空当前会话。

## XiaoAi 渲染管线

### 注入模型

XiaoAi 走**响应目标捕获 + Instruction 分片注入**：

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt` 的 `dispatchQueryToLLM()` 先启动 `LLMController.stream(query)`，再用 `shareIn(scope, SharingStarted.Eagerly, replay = Int.MAX_VALUE)` 预热共享流。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureResponseTargetHook.kt` 捕获宿主响应目标后，`targetReady.await()` 才会放行后续消费。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/RenderTextStreamCardHook.kt` 根据累计文本计算本次 `delta`，再构造宿主 `Instruction` 注入。

### 原生阻断

当前源码里实际安装的原生指令闸门是**内联在输入 Hook 内**的，没有独立的 Block 类：

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureInstructionInputHook.kt`：同一个 `beforeHook` 既采集 `SpeechRecognizer.RecognizeResult` / `Template.Query`，又在接管轮次内拦截其余原生 `Instruction`（白名单除外）。判定逻辑抽到 `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiInstructionGate.kt`，Hook 只做反射读取与动作执行。
- 原生 TTS 播放**不再有全局拦截 Hook**。旧版本曾安装 `BlockNativeTtsPlaybackHook` 并全局拦住原生播报，该安装点已移除，不要恢复。

历史上存在过的 `BlockNativeInstructionByWhitelistHook` 与 `BlockNativeTtsPlaybackHook` 两个类**已删除**：它们没有 `onHook` 调用点，属于死代码。白名单拦截逻辑并入上面的内联闸门后，旧类不再需要。

当前源码里没有单独的 `BlockNativeTextStreamHook` 或 `BlockNativeTtsStreamHook`；旧说法不再适用。

### 容器指令的处理

`Template.FrontendPage` 这类“流式卡片容器”目前**照常拦截**，没有放行分支：

- 旧实现曾凭 `loadUrl` 命中 `stream.bundle` 子串就放行。该判据不足：宿主自己的 `TemplateReactNativeCard.identifyBundle()` 只是把这个子串当卡片分类标志，它无法证明容器内层 `instructions` 没有副作用。
- 现在只做**脱敏诊断**：`XiaoaiStreamContainerInstruction.inspectShape(...)` 读出 loadType/paramType/URL 是否存在与是否命中标记/loadHtml 是否存在/cardType 是否存在/内层指令数量，全部以布尔量、枚举名和计数形式写入 `xlog`。日志不含 URL、HTML 与卡片正文。
- 若将来要重新引入放行，必须先证明内层指令无副作用；当前不移植旧放行路径。


### 生命周期

- `RenderTextStreamCardHook` 内部用 `Mutex + currentSession` 维护单个 `XiaoaiRenderSession`。
- 若响应目标缺失，`RenderTextStreamCardHook.render()` 只会上报 `renderTargetMissing` 事件；终帧时会清空 session，但不会自动降级到别的注入路径。
- 终帧会额外注入 `XiaoaiConfigProvider.RenderTextStreamCard.finalChunkText`，随后清空 session。
- `XiaoaiChatHook.onSessionReset()` 会同时清掉响应目标、`targetReady` 与渲染 session。

## 原生 takeover 路径

- `TurnMode.NativeTakeover` 在 `AbstractAssistantHook.handleCapturedQuery(...)` 中就会调用 `LLMController.stopCurrentRound(keepCurrentTurn = false)` 并直接返回。
- Breeno 侧因此不会调用 `renderStreamCard(...)`，`BlockNativeCardHook` 与 `SuppressCleanupHook` 也会对当前轮次放行原生回答与原生清理逻辑。
- XiaoAi 侧因此不会继续消费共享流；内联闸门也会因为 `ownsInjectedRoom(...)` 为 false 而对该轮次的原生 `Instruction` 全部放行，且此时没有任何 TTS 拦截逻辑参与。

## 原生固定文本播报（默认关闭）

`app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiFixedTextBroadcast.kt` 是为“先证明原生能播报”准备的显式测试入口，**默认关闭**（`actions.fixed_text_broadcast.business.enabled = false`），且只播报编译期常量文本，不接受 Agent 动态文本、不触发手机工具。

选定的发声入口是 `dh0.u.speakTts(String, kz.b)`（内部走 `r00.g.speak(...)`），停止入口是 `r00.g.stopTTS()`。

为什么不注入 `SpeechSynthesizer.SpeakStream`：对已核对 sha256 的 507012002 反编译后可见，`SpeakStream` 的消费方（`cb0.db`、`pb0.s`）只是把 payload 文本累加进 `SpeakContentManager`（`com.xiaomi.voiceassistant.instruction.utils.b2`），并不启动合成或播放；真正发声要经过 `TTSPlayView.onPlayClick()` → `ea0.n1.speakTts(text)` 或等价的 `dh0.u.speakTts`。只注入 `SpeakStream` 会是一个“看着接通、其实没有声音”的占位实现。

前置条件由 `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiFixedTextGate.kt` 判定：开关开启、宿主版本等于已核对版本、文本是固定常量、无在途播报、会话已绑定、目标未失效——任一不满足立即拒绝，不重试、不自动重放。

**证据边界**：`fixed_text_dispatched` 日志只证明反射调用已发起，**不证明设备发出了声音**。是否有声音只能由真机听感确认。

## 关键源码

### `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/`

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoConfigProvider.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoFeedbackAssembler.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/BlockNativeCardHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/CaptureInputHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/ResetConversationSignalHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/subhooks/SuppressCleanupHook.kt`

### `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/`

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiConfigProvider.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiInstructionGate.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiStreamContainerInstruction.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiRenderSession.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureInputHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureInstructionInputHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureResponseTargetHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/RenderTextStreamCardHook.kt`
