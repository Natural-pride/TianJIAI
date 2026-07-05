# Spring AI Flux 流式处理钩子函数完全指南

> 本文档基于 TianJIAI 项目（tj-aigc 模块）的实际代码，系统整理 Spring AI + Project Reactor 中 Flux 钩子函数的执行顺序、作用与用法。

---

## 一、整体架构概览

本项目基于 **Spring AI + Project Reactor** 实现流式聊天，核心调用链如下：

```
用户请求 → ChatClient.prompt().stream().chatResponse()
         → Flux<ChatResponse>（响应式流）
         → [钩子函数管道处理]
         → Flux<ChatEventVO>（前端消费的事件流）
```

### 核心代码位置

| 文件 | 路径 |
|------|------|
| Service 实现 | `tj-aigc/src/main/java/com/tianji/service/impl/ChatServiceImpl.java` |
| Controller | `tj-aigc/src/main/java/com/tianji/controller/ChatController.java` |
| 配置类 | `tj-aigc/src/main/java/com/tianji/config/SpringAIConfig.java` |
| 事件枚举 | `tj-aigc/src/main/java/com/tianji/enums/ChatEventTypeEnum.java` |
| 事件 VO | `tj-aigc/src/main/java/com/tianji/vo/ChatEventVO.java` |

---

## 二、概念厘清：Spring AI 回调接口 vs Reactor 钩子函数

> 这是两个完全不同层次的概念，容易混淆。

```
┌─────────────────────────────────────────────────────┐
│              Spring AI 层面（高层）                    │
│                                                     │
│    Advisor 拦截器链 / ToolCallback / Prompt 回调      │
│    (作用于请求构建和模型调用阶段)                       │
├─────────────────────────────────────────────────────┤
│            Project Reactor 层面（低层）                │
│                                                     │
│    doFirst / map / takeWhile / doOnComplete / ...    │
│    (作用于流式数据传输管道)                            │
└─────────────────────────────────────────────────────┘
```

### 区别对比

| 维度 | Spring AI 回调接口 | Reactor 钩子函数 |
|------|-------------------|-----------------|
| **归属** | Spring AI 框架提供 | Project Reactor 框架提供 |
| **作用时机** | 请求构建、模型调用前/后、工具执行 | 流式数据块传输过程 |
| **典型例子** | `Advisor` 拦截器、`ToolCallingCallback`、`Consumer<ChatClientRequest>` | `doFirst` / `map` / `takeWhile` / `doOnNext` / `doOnComplete` 等 |
| **操作对象** | `ChatClientRequestSpec`、`Prompt`、`ToolResponse` | `Flux<T>` 数据流中的每个元素 |
| **抽象层级** | 业务逻辑层（告诉 Spring AI **在做什么**） | 响应式流控制层（告诉管道**怎么流**） |

### 代码中的分界点

在项目的调用链中：

```java
.chatClient.prompt()
    .system(...)          // ↑ Spring AI 层面的配置
    .advisors(...)        // ↑ Advisor 拦截器（Spring AI 回调）
    .user(...)            // ↑ Spring AI 层面的配置
    .stream()
    .chatResponse()       // ← 分界线：从这里开始，Spring AI 返回 Flux<ChatResponse>

    .doFirst(...)         // ↓ 以下全部是 Reactor 钩子函数
    .takeWhile(...)       // ↓ 不属于 Spring AI 回调
    .map(...)             // ↓
    .doOnComplete(...)    // ↓
    .concatWith(...);     // ↓
```

### 一句话总结

**Spring AI 提供核心能力（调大模型），Reactor 提供流式传输管道**。钩子函数是 Reactor 管道的"阀门"，与 Spring AI 本身的回调接口没有关系——只是因为 Spring AI 选择用 Reactor 实现流式输出，所以我们才能在这上面挂这些钩子。

---

## 三、钩子函数执行总览图

```
订阅时机（立即执行）          每次大模型返回数据块时              流完成/异常/取消时
     │                              │                                   │
     ▼                              ▼                                   ▼
  doFirst ──────→ ┬──→ takeWhile ──→ map ──→ [后续钩子] ──→ concatWith
                   │         │                    │
                   │   返回false?             doOnNext
                   │         │                    │
                   │         ▼                    ▼
                   │    doOnCancel           doOnComplete / doOnError
                   │
               concatWith（初始即注册，最后才执行回调）
```

---

## 四、各钩子函数详解

### 1. `doFirst` — 订阅触发标记

**执行时机**：在 Flux 被**订阅（subscribe）时立即执行**，且在整个生命周期中只执行一次。此时还没有开始调用大模型 API。

**本质**：订阅 Spring AI 发出的事件流，注册监听器，类似于 Spring 的 ApplicationListener。

**项目中的用法** (`ChatServiceImpl.java:126-128`)：

```java
.doFirst(() -> {
    GENERATE_STATUS.put(sessionId, true);
})
```

标记该会话开始生成 AI 回复，后续前端可通过"停止生成"按钮调用 `stop(sessionId)` 清除此标记来实现中断输出。

> **注意**：`doFirst` 执行时还没有真正发起大模型调用，真正的调用在订阅建立之后才发生。

---

### 2. `takeWhile` — 流式输出开关门控

**执行时机**：**每次大模型返回一个数据块时触发**。注意底层**不是定时任务**，而是响应式推送。

**作用**：判断是否继续向下传递数据。

- 返回 `true`：继续处理后续钩子，将数据块推送给前端
- 返回 `false`：**立即切断流**，不再接收新数据（但底层与大模型的连接并不真正断开，因为大模型的流式输出已经算费、不能物理打断）

**项目中的用法** (`ChatServiceImpl.java:143`)：

```java
.takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
```

当用户点击"停止生成"时，`stop方法` 清除 `GENERATE_STATUS` 中的标记，`takeWhile` 检测到 `null` 返回 `false`，流被截断。

> **关键理解**：`takeWhile` 接收的参数类型与 `map` 一致（本项目中为 `ChatResponse`）。它在 `map` **之前**执行，决定是否要对这个数据块做后续处理。

---

### 3. `map` — 数据类型转换与增强替换

**执行时机**：**每次 `takeWhile` 返回 `true` 后**，拿到大模型返回的每个数据块时触发。

**本质**：有返回值的转换操作，可以**获取并替换**原始数据块。

**项目中的用法** (`ChatServiceImpl.java:146-153`)：

```java
.map(chatResponse -> {
    // 提取 AI 回复的文本片段
    String content = chatResponse.getResult().getOutput().getText();
    return ChatEventVO.builder()
            .eventData(content)
            .eventType(ChatEventTypeEnum.DATA.getValue())
            .build();
})
```

将 Spring AI 的 `ChatResponse` 转换为前端可消费的 `ChatEventVO` DATA 事件。

> **注意事项**：
> - `map` 的返回类型会成为下游钩子的入参类型
> - 如本例，经过 `map` 后，下游钩子拿到的不再是 `ChatResponse`，而是 `ChatEventVO`

---

### 4. `doOnNext` — 旁路监听（无返回值）

**执行时机**：同 `map`，每次拿到数据块时触发。

**本质**：**无返回值的旁路操作**，可以读取/增强数据，但**不能替换**原始数据。

**项目中未直接使用，补充示例**：

```java
.doOnNext(chatEventVO -> {
    // 记录日志、统计 Token、写入审计表等
    log.info("AI输出片段: {}", chatEventVO.getEventData());
})
```

**与 `map` 的核心区别**：

| 对比项 | map | doOnNext |
|--------|-----|----------|
| 有返回值 | ✅ | ❌ |
| 可替换数据 | ✅ | ❌ |
| 典型用途 | 类型转换/数据增强 | 日志/监控/旁路统计 |

---

### 5. `doOnCancel` — 流被取消时的回调

**执行时机**：**仅当 `takeWhile` 返回 `false` 时才会被触发**，正常完成时不会被调用。

**作用**：执行清理逻辑，比如通知其他服务"用户中断了生成"、记录中断审计日志等。

**项目中未直接使用，补充示例**：

```java
.doOnCancel(() -> {
    log.warn("用户 {} 中断了AI生成", sessionId);
    // 记录中断事件、释放额外资源等
})
```

> **重要**：`doOnCancel` 是在流被取消时调用的，而不是在调用 `stop()` 方法时。`takeWhile` 返回 `false` 是触发 `doOnCancel` 的条件。

---

### 6. `doOnComplete` — 流正常完成回调

**执行时机**：**大模型返回所有数据后**，整个流正常结束时触发。

**本质**：`Runnable` 类型，**无返回值**，不支持继续输出数据。

**项目中的用法** (`ChatServiceImpl.java:131-133`)：

```java
.doOnComplete(() -> {
    GENERATE_STATUS.remove(sessionId);
})
```

清除生成状态，释放 `ConcurrentHashMap` 中的内存。

> **关键理解**：`doOnComplete` 是"完成通知"，不是"追加数据"。想在流尾追加数据不能用 `doOnComplete`，必须用 `concatWith`。

**不会被执行的情况**：
- `takeWhile` 返回 `false`（用户中断）
- `doOnError` 被触发（发生异常）

---

### 7. `doOnError` — 异常回调

**执行时机**：流处理过程中发生任何异常时触发。

**项目中的用法** (`ChatServiceImpl.java:136-138`)：

```java
.doOnError(throwable -> {
    GENERATE_STATUS.remove(sessionId);
})
```

标记生成失败，防止状态内存泄漏。通常还需根据异常类型做差异化处理：

```java
.doOnError(throwable -> {
    GENERATE_STATUS.remove(sessionId);
    if (throwable instanceof WebClientResponseException e) {
        log.error("大模型API异常: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
    } else {
        log.error("AI生成异常", throwable);
    }
})
```

---

### 8. `concatWith` — 流尾追加数据（特殊钩子）

**注册时机**：**一开始订阅时就会执行**（注册到管道中）。

**回调执行时机**：**在所有钩子函数执行完毕之后**，包括 `doOnComplete` 之后。

**本质**：在当前 Flux 尾部**拼接**一个新的数据源，追加的这些数据**不会经历上游钩子**（如 `takeWhile`、`map`）。

**项目中的用法** (`ChatServiceImpl.java:157-159`)：

```java
.concatWith(Flux.just(ChatEventVO.builder()
        .eventType(ChatEventTypeEnum.STOP.getValue())
        .build()));
```

在大模型输出流的尾部追加一条 `STOP` 事件，通知前端关闭 SSE 连接。

> **关键理解**：
> - `concatWith` 追加的数据"绕过"所有上游钩子，直接输出给下游
> - 无论正常完成（`doOnComplete`）、异常（`doOnError`）还是中断（`takeWhile=false`），`concatWith` 的回调都会执行（有版本 bug 除外）
> - 追加时机在 `doOnComplete` **之后**

---

## 五、完整时序流程图

```
时间 ──────────────────────────────────────────────────────────────────────→

订阅阶段：
  doFirst() ─────────────────────────────────────────────────────────────→
  concatWith(注册) ──────────────────────────────────────────────────────→

大模型返回数据块1：
  ┌─ takeWhile(ChatResponse₁) → true
  │   └─ map(ChatResponse₁) → ChatEventVO₁ → 推送给前端
  │       └─ doOnNext(ChatEventVO₁) → 旁路监听
  └─

大模型返回数据块2（用户点击停止）：
  ┌─ takeWhile(ChatResponse₂) → false
  │   └─ 流被截断
  └─ doOnCancel() → 触发清理

流结束：
  doOnComplete()（正常完成时）
  doOnError()       （异常时）
       │
       ▼
  concatWith(回调执行) → 追加 STOP 事件 → 前端关闭 SSE
```

---

## 六、钩子函数速查表

| 钩子函数 | 执行时机 | 有返回值 | 数据可替换 | 本项目用途 |
|----------|---------|---------|-----------|-----------|
| `doFirst` | 订阅时立即执行一次 | ❌ | ❌ | 标记开始生成 |
| `takeWhile` | 每次数据块到达时 | ✅(boolean) | ❌ | 门控开关，中断流 |
| `map` | `takeWhile=true`后 | ✅ | ✅ | `ChatResponse` → `ChatEventVO` |
| `doOnNext` | 每次数据块到达时 | ❌ | ❌ | 日志/监控（项目未用） |
| `doOnCancel` | `takeWhile=false`时 | ❌ | ❌ | 中断清理（项目未用） |
| `doOnComplete` | 流正常结束时 | ❌ | ❌ | 清除生成状态 |
| `doOnError` | 异常时 | ❌ | ❌ | 清除生成状态 |
| `concatWith` | `doOnComplete`之后 | ✅ | ✅ | 追加 STOP 事件 |

---

## 七、完整管道代码参考

以下为项目中钩子函数的完整编排（`ChatServiceImpl.java:99-159`）：

```java
return chatClient.prompt()

        // 2.1 注入系统提示词和当前时间
        .system(promptSystem -> promptSystem
                .text(systemPromptConfig.getChatSystemMessage().get())
                .param("now", DateUtil.now()))

        // 2.2 注入多轮对话记忆的 conversationId
        .advisors(advisor -> advisor.param(
                AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                conversationId))

        // 2.3 设置用户问题
        .user(question)

        // 2.4 开启流式输出模式
        .stream()

        // 2.5 获取 ChatResponse 流
        .chatResponse()

        // 3. Reactor 流式处理

        // 3.1 请求大模型前，标记该会话正在生成
        .doFirst(() -> {
            GENERATE_STATUS.put(sessionId, true);
        })

        // 3.2 大模型输出完成，清除生成状态
        .doOnComplete(() -> {
            GENERATE_STATUS.remove(sessionId);
        })

        // 3.3 大模型输出异常，清除生成状态
        .doOnError(throwable -> {
            GENERATE_STATUS.remove(sessionId);
        })

        // 3.4 根据生成状态控制是否继续输出
        .takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))

        // 3.5 将每个 ChatResponse chunk 转换为前端可消费的 DATA 事件
        .map(chatResponse -> {
            String content = chatResponse.getResult().getOutput().getText();
            return ChatEventVO.builder()
                    .eventData(content)
                    .eventType(ChatEventTypeEnum.DATA.getValue())
                    .build();
        })

        // 3.6 在流尾部追加一条 STOP 事件
        .concatWith(Flux.just(ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.STOP.getValue())
                .build()));
```

---

## 八、版本注意事项（Spring AI 1.0.0-M6）

已知 Bug：**每个新对话的第一次请求，如果通过 `takeWhile` 打断（用户中断），`concatWith` 不会被触发**；第二次及以后的请求可以正常触发。

**临时 workaround**：在 `doOnCancel` 中补偿发送 `STOP` 事件给前端：

```java
.doOnCancel(() -> {
    // 通过 SSE 通道手动推送 STOP 事件
    sseEmitter.send(ChatEventVO.builder()
            .eventType(ChatEventTypeEnum.STOP.getValue())
            .build());
})
```

> 注意：由于项目直接使用 `Flux` 返回值，不持有 `SseEmitter` 引用，实际补偿需通过额外的事件总线或消息通道实现，或直接升级到修复版本。

---

## 九、最佳实践建议

### 1. 状态清理务必成对出现

```java
.doOnComplete(() -> GENERATE_STATUS.remove(sessionId))
.doOnError(throwable -> GENERATE_STATUS.remove(sessionId))
```

避免因异常或中断导致状态常驻内存引发内存泄漏。

### 2. `concatWith` 与 `doOnComplete` 职责分工明确

- `concatWith` → **追加数据**（可输出给前端）
- `doOnComplete` → **清理/通知**（不能输出数据）

### 3. 分布式环境改造

本项目使用 `ConcurrentHashMap` 存储生成状态，仅适用于单机。**分布式部署时需替换为 Redis**：

```java
// 改造示例
.doFirst(() -> redisTemplate.opsForValue().set(
        "chat:generate:" + sessionId, "true", 5, TimeUnit.MINUTES))
.takeWhile(s -> "true".equals(redisTemplate.opsForValue().get(
        "chat:generate:" + sessionId)))
```

### 4. `doOnNext` 做轻量操作

避免在其中执行耗时逻辑（如同步数据库写入、外部 API 调用），否则会阻塞流式推送。耗时操作应使用 `subscribeOn` 切换到异步线程池执行。

### 5. 异常粒度细化

```java
.doOnError(throwable -> {
    GENERATE_STATUS.remove(sessionId);
    if (throwable instanceof WebClientResponseException e) {
        log.error("大模型API异常: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
    } else if (throwable instanceof TimeoutException) {
        log.error("调用大模型超时: sessionId={}", sessionId);
    } else {
        log.error("AI生成未知异常: sessionId={}", sessionId, throwable);
    }
})
```

---

## 十、补充说明

### 关于停止生成功能的实现机制

本项目的"停止生成"并非真正断开与大模型的底层连接，而是**停止将数据推送给前端**。大模型可能仍在后台生成剩余内容并计费，这是流式场景下的通用权衡方案。

如需真正中断大模型调用，需使用响应式流的 `dispose()` 方法取消订阅，但这取决于大模型客户端的实现是否支持断开底层 HTTP 连接。

---

> **文档版本**：v1.1
> **最后更新**：2026-07-05
> **关联项目**：TianJIAI / tj-aigc
> **适用版本**：Spring AI 1.0.0-M6 / Project Reactor 3.x
