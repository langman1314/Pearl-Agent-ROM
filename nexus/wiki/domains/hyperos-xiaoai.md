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

实际接线：`XiaoaiChatHook.installInputHooks` → `XiaoaiFixedTextTrigger.onInput`（仅精确短语 **Nexus 播报自检**）→ 同 dialog 的 `CaptureResponseTargetHook` → `onTargetCaptured` → `XiaoaiFixedTextAdapter.broadcastFixedText`。匹配且配置开启时，在正常 reserve/submit 前消费输入，不调用模型或手机工具。输出协议无动态文本参数，只允许固定文本端口。每次短语最多派发一次，重复 target 事件不重放；派发前两次读取 injected turn/dialog 和 TTL 目标有效性。结算按原 turnId/dialogId 清理，旧回调不能清理新 owner。

**当前实际宿主端口硬性禁用**（`HOST_BACKEND_ENABLEABLE = false`），配置 true 也不能发声；明确记录 `BACKEND_UNVERIFIED`，且不会建立自检轮次。原先未经确认的反射调用及全局停止已移除。假异步宿主验证了调用先返回、完成后释放、确认取消、拒绝无归属停止等状态机；这不是 Xposed/真机集成成功。无回调或停止无确认时保留所有权，不用定时器解锁。

后续启用/恢复：本轮不能启用实际播报。先由队长裁决并补齐安全宿主端口：可归属的 session、完成/失败回调桥、定向且有完成确认的取消、宿主线程约束及无回调分支；通过独立复核后再获用户设备授权。届时在该版本的有效配置 `actions.fixed_text_broadcast.business.enabled` 改 true（当前无用户 UI 开关，须明确有效缓存/打包配置来源，不只改一个未生效 asset），唤醒小爱并说上述短语一次；须同会话目标到达才能派发。恢复为 false 并确认有效配置已恢复，按获准方式重载宿主。当前只能离线使用假端口复现链路，不能把上述未来步骤写成已可在手机运行。

不注入 `SpeechSynthesizer.SpeakStream`：该版本里它的消费方（`cb0.db`、`pb0.s`）只把文本累加进 `SpeakContentManager`，不启动合成或播放；真正发声要经过 `TTSPlayView.onPlayClick()` → `ea0.n1.speakTts(text)`。只注入 `SpeakStream` 会是没有声音的占位实现。

`XiaoaiFixedTextGate` 判定开关、宿主版本、固定文本、在途状态、会话绑定与目标有效性；任一不满足立即拒绝，不重试、不自动重放。**`fixed_text_dispatched` 日志只证明反射调用已发起，不证明有声音。**

## 已知边界

- 本地 APK 507012002 SHA-256：`326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406`。局部 jadx 输出证据：`dh0.u.java:14-33` 返回 void，linked/needTTS-off 分支同步 `onPlayFinish(true)` 不等于听见声音；`kz.b.java:4-10` 为三方法回调接口；`r00.o.java:1223-1249` 有无回调 early-return，且 `:1246` 的 canStop 参数实际为 `!arrayList.contains(enginePrefix)`，**不能把上游 speak 的 false 误当作 canStop=false**。`r00.z0.java:127-179` 设置 canStop/监听器并产生 sessionId，但上游 void 入口丢弃该 ID。
- `r00.g.java:588-599` 的 stopTTS 仅在 canStop 时异步提交；`:111-115` 的停止体作用于多个全局 track，不提供本次播报的完成确认。`r00.z0.java:35-79` 转发回调；`o20.e.java:186-277` 的真实音频事件触发 start/finish；`y00.j1.java:733-740` 提交异步 Runnable。真实 callback 最终线程未确证，必须视为可能非调用线程，不能宣称主线程。状态机已用另一线程回调测试，但未验证真实桥接。
- 尚缺 session 归属与安全停止确认、无回调失败可判定性、真实回调桥及线程保证；所以真实端口不可启用，而非仅默认 false。实际声音、结束、打断、再次唤醒全部待后续设备授权。
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
