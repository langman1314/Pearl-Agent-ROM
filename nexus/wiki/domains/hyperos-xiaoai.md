# HyperOS / XiaoAi Domain

## 当前架构

XiaoAi 负责原生唤醒、待机、麦克风、VAD 与 ASR。Nexus 在 XiaoAi 的 instruction 分发边界读取最终 ASR，把用户输入提交给主 App 的 Agent Runtime；Nexus 是接管轮次的思考与执行主体。XiaoAi 的 UI 仅作为文本输出适配器。

## 输入

`CaptureInstructionInputHook` 安装在版本配置指定的全局 `handle(Instruction, String)`：

- `SpeechRecognizer.RecognizeResult` 是首选输入。仅当 payload 的 `isFinal()` 为 true，且 `getResults()` 中存在非空 `getText()` 时，才产生 `FINAL_ASR` 输入。
- `Template.Query` 是兼容兜底，不是首选 ASR 证据。
- `OperationManager.setQueryInfo` 是第二兼容兜底。
- 三个来源通过 `XiaoaiInputDeduplicator` 在短时间窗口内合并；窗口后完全相同的新问题仍会放行。
- 解析失败、未知 payload 或非 final ASR 均 fail-open，不阻断原生路径。

每个输入 requestId 包含宿主进程 epoch、单调输入序列、来源、turnId 与长度前缀 roomId，避免进程重启、重复文本和兼容入口之间的身份碰撞。

## Ownership 与阻断

`AbstractAssistantHook` 先向 `AgentRuntimeService` reserve request；reserve 成功后才建立 provisional Nexus owner。规则决策为 Nexus 时 `submitReserved` 原子 commit；规则读取异常或 NativeTakeover 会 release reservation 并清理 owner。

原生 instruction 只在 `ActiveTurnStore` 拥有同一个 dialog 时阻断。不相关 dialog 永远放行。ASR 生命周期 instruction 和注入 tag 指令永远放行。无法关联 dialog 的全局 TTS playback hook 不再安装。

阻断判定实现在 `XiaoaiInstructionGate`（纯逻辑，可单测），由 `CaptureInstructionInputHook` 在同一个回调内调用——采集与拦截必须同源，因为同一方法上两个 Hook 的回调顺序在不同运行时并不稳定。

`Template.FrontendPage` 流式卡片容器**照常拦截**：`loadUrl` 命中 `stream.bundle` 子串不足以证明内容器内层指令无副作用（宿主自己只用该子串做卡片分类）。拦截时记录脱敏 shape 到 `xlog`，用于在真机日志里分辨正常容器与夹带内层指令/伪装 URL 的容器；日志不含 URL、HTML 与正文。

## 输出

`CaptureResponseTargetHook` 捕获宿主响应目标。`XiaoaiResponseTargetRegistry` 按 dialog 保存目标并只唤醒相同 dialog 的等待者；目标有 TTL，reset 会取消等待和清空目标。每个渲染帧使用明确捕获的 target，不能被其他 dialog 的后续捕获覆盖。

`RenderTextStreamCardHook` 构造带 dialogId 和 Nexus 注入 tag 的 `Template.ToastStream` instruction。final 后按 turnId + dialogId 条件清理 owner，旧超时或旧 final 无权清理后继 turn。

### 原生固定文本播报（默认关闭）

`XiaoaiFixedTextBroadcast` 提供一个默认关闭的显式测试入口，只播报编译期固定常量文本。发声入口是 `dh0.u.speakTts(String, kz.b)`，停止入口是 `r00.g.stopTTS()`。

不注入 `SpeechSynthesizer.SpeakStream`：该版本里它的消费方（`cb0.db`、`pb0.s`）只把文本累加进 `SpeakContentManager`，不启动合成或播放；真正发声要经过 `TTSPlayView.onPlayClick()` → `ea0.n1.speakTts(text)`。只注入 `SpeakStream` 会是没有声音的占位实现。

`XiaoaiFixedTextGate` 判定开关、宿主版本、固定文本、在途状态、会话绑定与目标有效性；任一不满足立即拒绝，不重试、不自动重放。**`fixed_text_dispatched` 日志只证明反射调用已发起，不证明有声音。**

## 已知边界

- 当前已实现文本 UI 输出；独立可靠 TTS 仍未获得 507012002 真机固定文本验证，因此不宣称可用。适配器代码已就绪但真机发声与否未经验证，日志不能替代听感证据。
- 指令闸门只有源码逻辑单测覆盖；Xposed 是否真的挂上宿主方法、宿主是否真的传入预期字段，仍只能靠真机 `nexus-x-log` 确认。两层覆盖不可互相代替。
- 浮窗 detach 仍会触发 conversation reset；长任务与窗口生命周期的完全解耦仍需真机交互设计与验证。
- 507012002 的最终 ASR getter 由 Xiaomi API 类签名和该版本真实日志交叉支持；仍必须在安装候选 APK 后验证唤醒词、长按、连续轮次与离线可用路径。

## 关键源码

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AssistantInputIdentity.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/FinalAsrPayloadDecoder.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiInputDeduplicator.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiResponseTargetRegistry.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiInstructionGate.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiStreamContainerInstruction.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiFixedTextGate.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiFixedTextBroadcast.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/CaptureInstructionInputHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/subhooks/RenderTextStreamCardHook.kt`
