# ChatServiceImpl 流式响应核心链路解析

> 本文档聚焦 `ChatServiceImpl#chat` 方法中三个关键代码片段的深度解析：`chatResponse()` 流、生命周期钩子 (`doFirst` / `doOnComplete` / `doOnError`)、以及 `takeWhile` 截断开关。三者协同实现了 **AI 流式输出 + 用户中途停止 + 状态自动清理** 的完整闭环。

---

## 🎯 一图看懂全貌

```
┌─────────────────────────────────────────────────────────────────────┐
│                       客户端发起 SSE 订阅                            │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│   chatClient.prompt().system().advisors().user().stream()           │
│                                │                                    │
│                                ▼                                    │
│                     .chatResponse()  ◄── Flux<ChatResponse>         │
│                                │                                    │
│   ┌────────────────────────────┼────────────────────────────────┐   │
│   │ ① doFirst   (订阅即触发)   │   把 GENERATE_STATUS[xxx]=true  │   │
│   │                            ▼                                │   │
│   │ ② takeWhile (每 chunk 检查) │   Optional.ofNullable(...).orElse(false)│
│   │                            │   ── true  → 放行              │   │
│   │                            │   ── false → 截断！             │   │
│   │                            ▼                                │   │
│   │ ③ map      (ChatResponse → ChatEventVO DATA)                │   │
│   │                            ▼                                │   │
│   │ ④ concatWith(Flux.just(STOP 事件))                          │   │
│   └────────────────────────────┼────────────────────────────────┘   │
│                                │                                    │
│              ┌─────────────────┼─────────────────┐                  │
│              ▼                 ▼                 ▼                  │
│        正常结束 onComplete  异常 onError      被 takeWhile 截断      │
│              │                 │                 │                  │
│              ▼                 ▼                 ▼                  │
│       doOnComplete        doOnError        doOnComplete              │
│       清理状态             清理状态         清理状态（截断=完成）      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 一、`chatResponse()` —— 为什么是 `Flux<ChatResponse>` 而不是 `Flux<String>`

### 1.1 它是什么

`chatResponse()` 是 **Spring AI** 中 `ChatClient.StreamResponseSpec` 接口提供的方法，返回 **`Flux<ChatResponse>`** 类型。每个元素是一个 `ChatResponse` 对象，包含文本片段 + 完整元数据。

### 1.2 调用链路

```java
chatClient.prompt()      // 1. PromptSpec
    .system(...)         // 2. 系统提示词
    .advisors(...)       // 3. 对话记忆 Advisor
    .user(question)      // 4. 用户问题
    .stream()            // 5. 进入流式模式（关键节点！）
    .chatResponse()      // 6. 终止方法：选择返回 Flux<ChatResponse>
```

> ⚠️ 必须先调用 `.stream()` 才能用 `chatResponse()` / `content()`。这两个是 `StreamResponseSpec` 的终止方法。

### 1.3 两个终止方法的对比

| 方法 | 返回类型 | 适用场景 |
|------|---------|---------|
| `.chatResponse()` | `Flux<ChatResponse>` | 需要元数据（token用量、toolCalls、model名） |
| `.content()` | `Flux<String>` | 纯文本流，简单够用 |

### 1.4 `ChatResponse` 内部结构

```
ChatResponse
├── getResult()           → Result
│   └── getOutput()       → AssistantMessage
│       ├── getText()     ← 流式输出的核心（当前项目用到的）
│       ├── getToolCalls()→ 工具调用列表
│       └── getMetadata() → 消息级元数据
├── getMetadata()         → 响应级元数据
│   ├── getUsage()        → token 用量（prompt/completion/total）
│   ├── getModel()        → 实际使用的模型名
│   └── getId()           → 响应 ID
└── getResults()          → 多结果列表（多候选场景）
```

### 1.5 为什么本项目选 `chatResponse()` 而非 `content()`

当前 `.map` 逻辑确实只用到了 `getText()`，理论上 `.content()` 也能完成。但选 `chatResponse()` 是**预留扩展口子**：

| 未来扩展需求 | 需要的数据 | 哪个能拿到 |
|------------|-----------|-----------|
| token 用量统计（计费/限流） | `metadata.getUsage()` | `chatResponse()` ✅ |
| Function Calling | `output.getToolCalls()` | `chatResponse()` ✅ |
| 推理过程展示（DeepSeek 等思维链） | `metadata.getReasoningContent()` | `chatResponse()` ✅ |
| A/B 实验记录实际模型 | `metadata.getModel()` | `chatResponse()` ✅ |
| 仅展示文本 | `getText()` / `.content()` | 两个都行 ✅ |

---

## 二、`doFirst` / `doOnComplete` / `doOnError` —— 互斥钩子，不是都执行

### 2.1 核心结论

它们**不是按顺序全部执行**，而是**根据流最终的终止状态选其一**：

| 流终止状态 | doFirst | doOnComplete | doOnError |
|-----------|---------|--------------|-----------|
| ✅ 正常完成（最后一个 chunk 输出） | ✅ 执行 | ✅ 执行 | ❌ 不执行 |
| ❌ 抛异常（大模型报错、网络中断） | ✅ 执行 | ❌ 不执行 | ✅ 执行 |
| ⛔ 被 `takeWhile` 截断（用户点停止） | ✅ 执行 | ✅ 执行 | ❌ 不执行 |

> 也就是说：**`doFirst` 必执行**（订阅瞬间），然后 **`doOnComplete` 和 `doOnError` 二选一**，互斥。

### 2.2 三个钩子的语义

#### `doFirst` —— 订阅即触发，最先执行

```java
.doFirst(() -> {
    GENERATE_STATUS.put(sessionId, true); // 标记生成开始
})
```

- 在 **订阅（subscribe）时**立即触发，甚至在向大模型发起请求**之前**就执行
- 无论后续流是成功、失败、还是被中断，**一定执行一次**
- 作用：先把"正在生成"的标记打上，让前端可以发"停止生成"请求

#### `doOnComplete` —— 流正常结束（含截断）触发

```java
.doOnComplete(() -> {
    GENERATE_STATUS.remove(sessionId); // 清除生成状态
})
```

- 触发时机：
  - 大模型自然输出完最后一个 token
  - `takeWhile` 截断（用户点停止，Reactor 也会发出 `onComplete`）
  - `concatWith` 拼接的 STOP 事件正常发完

#### `doOnError` —— 流发生异常时触发

```java
.doOnError(throwable -> {
    GENERATE_STATUS.remove(sessionId);
})
```

- 触发时机：
  - 大模型 API 返回 4xx/5xx
  - 网络超时、连接断开
  - JSON 解析失败
  - Redis 取历史消息失败等

### 2.3 时序图

```
订阅发生
   │
   ▼
┌──────────────┐
│   doFirst    │ ◄── 必触发（无条件）
└──────────────┘
   │
   ▼
大模型输出 chunk 1, 2, 3 ... （经 takeWhile / map / concatWith）
   │
   ├─── 正常完成 ─────────────────► doOnComplete  ✅
   │                                     │
   │                                     ▼
   │                                  onComplete
   │
   ├─── 中途抛异常 ────────────────► doOnError     ✅
   │                                     │
   │                                     ▼
   │                                  onError
   │
   └─── takeWhile 返回 false ──────► doOnComplete ✅（截断也视作完成）
```

### 2.4 易踩坑点

#### ⚠️ 用户点"停止"时 `doOnError` 不会执行

业务上希望停止时也要清除状态，看 `takeWhile` 的精妙设计：

```java
.takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
```

`stop()` 方法 `remove` 后，下次 `takeWhile` 检测到值变成 `null`（→ `false`），流被截断。截断等价于正常完成，所以 **`doOnComplete` 会执行**，状态被清除。✅

#### ⚠️ `doFirst` 自身抛异常

`doFirst` 的 lambda 抛异常会直接转成 `onError`，后面 `doOnComplete` 不执行，`doOnError` 会执行。

#### ⚠️ `doFirst` vs `doOnSubscribe` 的区别

- `doFirst`：组装链时最先加入的最先执行，订阅瞬间触发
- `doOnSubscribe`：在订阅发生时执行，但**遵循上下游位置**（下游注册就后执行）

---

## 三、`takeWhile` —— 反应式开关，实现"中途拔插头"

### 3.1 核心代码

```java
.takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
```

### 3.2 逐段拆解

#### `takeWhile` 是什么

```java
Flux<T> takeWhile(Predicate<? super T> predicate)
```

- 每来一个元素就用 Predicate 测一下
- Predicate 返回 `true` → 元素放行
- Predicate 返回 `false` → **立即终止流**，后续元素直接丢弃，发出 `onComplete`

> ⚠️ 注意区别于 `filter`：`filter` 只是丢弃元素、流继续；`takeWhile` 是**截断**。

#### 参数 `s`

`s` 是流经的每个元素（这里是 `ChatResponse` 对象）。但**这个参数实际没被用上**，Predicate 真正判断的依据是**外部的 `GENERATE_STATUS` Map**。

#### `Optional.ofNullable(...).orElse(false)`

```java
GENERATE_STATUS.get(sessionId)    // 可能返回 true 或 null（被 remove 后）
   ↓
Optional.ofNullable(...)          // 把 null 包装成空 Optional
   ↓
.orElse(false)                    // null → false，非 null → 原始值
```

**等价写法**：
```java
return Boolean.TRUE.equals(GENERATE_STATUS.get(sessionId));
```

### 3.3 为什么必须用 `Optional.ofNullable`

写法对比：

```java
// 写法 A：直接 boolean 自动拆箱 ❌ NPE 风险
.takeWhile(s -> GENERATE_STATUS.get(sessionId))

// 写法 B：显式 null 判断
.takeWhile(s -> {
    Boolean status = GENERATE_STATUS.get(sessionId);
    return status != null && status;
})

// 写法 C：当前项目写法 ✅
.takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))

// 写法 D：更简洁的等价写法
.takeWhile(s -> Boolean.TRUE.equals(GENERATE_STATUS.get(sessionId)))
```

`Map.get()` 在 key 不存在时返回 `null`，**自动拆箱 `null` 会抛 NPE**，而停止时值就是 `null`，正是 `takeWhile` 截断的前提条件。

### 3.4 关键细节

#### 截断"几乎"实时

`takeWhile` 在**每个 chunk 到达时**都检查状态。一旦用户调 `stop()`，下一个 chunk 到来时立刻返回 false。时间延迟 ≈ **下一个 chunk 的间隔**（通常几十~几百毫秒）。

#### 不会真的"拔网线"

`takeWhile` 只是 **Reactor 流层面的截断**，不会取消已经发往大模型的 HTTP 请求。大模型服务端可能还在生成 token，只是结果不再推送给前端。要真正取消请求需用 `Disposable.dispose()`。

#### 为什么不选 `filter`

| 操作符 | 行为 | 适用场景 |
|--------|------|---------|
| `filter` | 不满足的元素**丢弃**，流继续 | 数据清洗 |
| `takeWhile` | 不满足的元素**截断整个流** | 紧急停止 |

业务需求是"立刻停止后续所有输出"，所以选 `takeWhile`。

---

## 四、完整交互流程（用户停止生成的完整闭环）⭐

### 4.1 数据载体：`GENERATE_STATUS`

```java
private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();
// Key = sessionId，Value = true(正在生成) / null(已停止，已被 remove)
```

### 4.2 完整时序图

```
                  用户点击"开始对话"
                          │
                          ▼
            subscribe() 触发 doFirst
            GENERATE_STATUS.put(sessionId, true)
                          │
                          ▼
              大模型 chunk 1 ──► takeWhile 检查 ──► true  → 放行 → 推给前端
              大模型 chunk 2 ──► takeWhile 检查 ──► true  → 放行 → 推给前端
              大模型 chunk 3 ──► takeWhile 检查 ──► true  → 放行 → 推给前端
                          │
                          │   ← 此时用户点了"停止生成"
                          │
                  stop() 被调用
                  GENERATE_STATUS.remove(sessionId)
                  （Map 中该 sessionId 不再存在）
                          │
                          ▼
              大模型 chunk 4 ──► takeWhile 检查
                                get() 返回 null
                                              ──► Optional.ofNullable(null)
                                              ──► .orElse(false) → false
                                              ──► takeWhile 截断！
                                              ──► 发出 onComplete
                                              ──► doOnComplete 执行
                                              ──► GENERATE_STATUS.remove（其实已被 remove）
                          │
                          ▼
                  前端再不会收到 chunk 4 及其之后的内容
                          │
                          ▼
                  服务端继续收到后续 chunk，但都被 takeWhile 丢弃
                  直到大模型正常结束 → onComplete → doOnComplete
```

### 4.3 三段代码如何协同

| 代码片段 | 角色 | 解决的问题 |
|---------|------|-----------|
| `doFirst` | 开关"打开" | 订阅瞬间打上"正在生成"标记，让前端可发停止请求 |
| `takeWhile` | 开关"检查" | 每个 chunk 都看一眼开关状态，状态没了就截断 |
| `doOnComplete` / `doOnError` | 开关"关闭" | 不论怎样结束都清理标记，避免内存泄漏 |

形成 **打开 → 检查 → 关闭** 的完整生命周期管理。

---

## 五、关键设计点总结

### 5.1 反应式优势

- **非阻塞**：流式推送不阻塞 Tomcat 线程
- **背压友好**：前端慢的话消费就会慢，不会压垮服务端
- **可中断**：通过 `takeWhile` + 共享状态实现优雅停止

### 5.2 线程安全

- `GENERATE_STATUS` 用 `ConcurrentHashMap` 保证多用户并发安全
- `static` 修饰全局共享同一份状态
- 当前是单机内存版，分布式环境建议改用 Redis（代码注释也明确提到）

### 5.3 防御性编程

| 风险点 | 防御手段 |
|-------|---------|
| `Map.get` 返回 null 导致 NPE | `Optional.ofNullable(...).orElse(false)` |
| 流异常时状态未清理 | `doOnError` 兜底 remove |
| 用户中途打断时状态残留 | `takeWhile` 截断触发 `doOnComplete` 清理 |
| 大模型持续输出但前端已断 | `takeWhile` 截断后丢弃后续 chunk |

### 5.4 设计哲学

整个流式链路遵循 **"留口子"原则**：

- 用 `chatResponse()` 而非 `content()` —— 保留元数据访问能力
- 用 `doFirst`/`doOnComplete`/`doOnError` 三件套 —— 覆盖所有终止场景
- 用 `takeWhile` 而非 `filter` —— 真正能"立刻停下"
- 用 `Optional.ofNullable` 而非直接拆箱 —— 防御性 null 处理

**每一处选择都不是最简的，但都是最稳的。**

---

## 📚 相关源码位置

| 文件 | 关键代码 |
|------|---------|
| [ChatServiceImpl.java:99-117](file://F:\CodeRepository\AI\TianJIAI\tj-aigc\src\main\java\com\tianji\service\impl\ChatServiceImpl.java#L99-L117) | `chatClient.prompt().stream()` 调用链 |
| [ChatServiceImpl.java:120](file://F:\CodeRepository\AI\TianJIAI\tj-aigc\src\main\java\com\tianji\service\impl\ChatServiceImpl.java#L120) | `.chatResponse()` 流选择 |
| [ChatServiceImpl.java:126-140](file://F:\CodeRepository\AI\TianJIAI\tj-aigc\src\main\java\com\tianji\service\impl\ChatServiceImpl.java#L126-L140) | `doFirst` / `doOnComplete` / `doOnError` 三件套 |
| [ChatServiceImpl.java:145](file://F:\CodeRepository\AI\TianJIAI\tj-aigc\src\main\java\com\tianji\service\impl\ChatServiceImpl.java#L145) | `takeWhile` 反应式开关 |
| [ChatServiceImpl.java:174-176](file://F:\CodeRepository\AI\TianJIAI\tj-aigc\src\main\java\com\tianji\service\impl\ChatServiceImpl.java#L174-L176) | `stop()` 停止方法实现 |

---

> 💡 **推荐阅读顺序**：先看第四章流程图建立整体认知 → 再看第一章理解 `chatResponse` → 然后看第二章钩子执行逻辑 → 最后看第三章 `takeWhile` 的细节。这种自顶向下的方式能最快建立完整的认知模型。