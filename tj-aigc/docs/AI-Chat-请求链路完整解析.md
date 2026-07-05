# AI 聊天系统请求链路完整解析

> 本文档基于 `tj-aigc` 微服务的实际代码，详细解析整个 AI 聊天的请求链路设计，涵盖底层配置、会话管理、流式响应、多轮对话记忆等核心模块。

---

## 📋 整体架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端 (前端)                           │
│  创建会话: POST /session?n=3                                   │
│  流式聊天: POST /chat (SSE 流)                                  │
│  停止聊天: POST /chat/stop?sessionId=xxx                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Controller 层                              │
│  SessionController ──► ChatSessionService                       │
│  ChatController    ──► ChatService                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Service 层                                │
│  ChatSessionServiceImpl ──► 创建会话、获取热门话题               │
│  ChatServiceImpl         ──► 调用 Spring AI、处理流式响应       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Spring AI 层                               │
│  ChatClient ──► MessageChatMemoryAdvisor ──► RedisChatMemory   │
│                  (多轮对话记忆管理)         (Redis 持久化)       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     数据存储层                                   │
│  MySQL (chat_session 表) ──► 会话持久化                         │
│  Redis (CHAT:xxx 键)     ──► 聊天消息历史存储                   │
│  Nacos                   ──► 系统提示词动态配置                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔄 链路一：创建会话

### 1.1 请求入口 — SessionController

```java
@RestController
@RequestMapping("/session")
public class SessionController {

    private final ChatSessionService chatSessionService;

    /**
     * 新建会话
     * 
     * 设计思路：
     * - 使用 POST 而非 GET，因为要创建新资源（会话）
     * - 参数 n 控制返回的热门话题数量，默认 3 个
     * - 返回 SessionVO 包含会话 ID、AI 助手信息、示例话题
     */
    @PostMapping
    public SessionVO createSession(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return chatSessionService.createSession(num);
    }

    /**
     * 获取热门会话
     * 
     * 注意：这个接口与"新建会话"分离，支持独立获取热门示例
     * 前端可在不创建会话的情况下展示推荐话题
     */
    @GetMapping("/hot")
    public List<SessionVO.Example> getHotSessions(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return chatSessionService.getHotSessions(num);
    }
}
```

**关键设计点**：
- `@RequestParam` 用于简单参数，比 `@RequestBody` 更轻量
- 默认值保证前端不传参时也能正常工作
- 成功响应返回 `SessionVO`（统一响应包装器会包装成 `R<SessionVO>`）

---

### 1.2 业务实现 — ChatSessionServiceImpl

```java
@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    // 从 Nacos/配置文件注入的会话配置（tj.ai.session.title、describe、examples）
    private final SessionProperties sessionProperties;

    @Override
    public SessionVO createSession(Integer num) {
        // 步骤 1：拷贝配置属性到 VO
        SessionVO sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);

        // 步骤 2：随机选取热门话题（防御 null 和空列表）
        List<SessionVO.Example> examples = sessionProperties.getExamples();
        if (CollUtil.isEmpty(examples)) {
            sessionVO.setExamples(Collections.emptyList());
        } else {
            sessionVO.setExamples(RandomUtil.randomEleList(examples, num));
        }

        // 步骤 3：生成会话 ID（UUID，无意义且安全）
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        // 步骤 4：持久化到数据库（只存 sessionId 和 userId）
        ChatSession chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())  // ThreadLocal 获取当前用户
                .build();
        save(chatSession);
        
        return sessionVO;
    }
}
```

**逐行解析**：

| 代码 | 作用 | 为什么这样写 |
|------|------|--------------|
| `BeanUtil.toBean(sessionProperties, SessionVO.class)` | 配置对象 → VO 对象 | 避免手动逐个 setter，一行搞定属性拷贝 |
| `CollUtil.isEmpty(examples)` | 防御性判空 | 配置中心可能没配置 examples，避免 NPE |
| `RandomUtil.randomEleList(examples, num)` | 随机选取 N 个 | 每次新用户看到的示例不同，增加多样性 |
| `IdUtil.fastSimpleUUID()` | 生成 32 位 UUID | 全局唯一，不包含用户信息，安全且隐私 |
| `UserContext.getUser()` | 从 ThreadLocal 获取当前用户 | 拦截器解析 Token 后存入，业务代码无需传参 |
| `save(chatSession)` | MyBatis-Plus 保存到数据库 | 会话持久化后支持服务重启恢复 |

**为什么使用两种 ID**：
- `id`：数据库自增主键，内部使用，高效关联查询
- `sessionId`：业务 UUID，暴露给前端，无意义且安全（不包含用户信息）

---

### 1.3 数据传输对象 — SessionVO

```java
@Data
@Builder
public class SessionVO {
    private String sessionId;           // 会话 ID（UUID）
    private String title;               // AI 助手标题："AI智能助手"
    private String describe;            // AI 助手描述："您好，我是您的智能学习助手..."
    private List<Example> examples;     // 示例话题列表

    @Data
    public static class Example {
        private String title;           // "课程推荐"
        private String describe;        // "我想学习Java，有什么课程推荐？"
    }
}
```

**前端渲染效果**：
```
┌─────────────────────────────────────┐
│  AI智能助手                          │
│  您好，我是您的智能学习助手...        │
├─────────────────────────────────────┤
│  试试这些问题：                      │
│  ┌─────────────────────────────┐   │
│  │ 课程推荐                     │   │
│  │ 我想学习Java，有什么课程推荐？│   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 职业规划                     │   │
│  │ 零基础如何成为后端开发？      │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

---

### 1.4 配置驱动 — SessionProperties

```java
@ConfigurationProperties(prefix = "tj.ai.session")
@Data
public class SessionProperties {
    private String title = "AI智能助手";
    private String describe = "您好，我是您的智能学习助手...";
    private List<SessionVO.Example> examples = new ArrayList<>();
}
```

**对应 Nacos 配置**：
```yaml
tj:
  ai:
    session:
      title: "AI智能助手"
      describe: "您好，我是您的智能学习助手，可以回答课程、学习、职业规划等各类问题"
      examples:
        - title: "课程推荐"
          describe: "我想学习Java，有什么课程推荐？"
        - title: "职业规划"
          describe: "零基础如何成为后端开发？"
        - title: "学习方法"
          describe: "如何高效学习SpringBoot？"
```

**为什么用配置而非硬编码**：
- **动态更新**：修改配置中心，无需重启服务
- **多环境支持**：开发/测试/生产不同配置
- **运营自主**：运营人员可自行调整 AI 形象和示例

---

## 🔄 链路二：流式聊天

### 2.1 请求入口 — ChatController

```java
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    /**
     * 流式聊天接口
     * 
     * 请求格式：POST /chat，Content-Type: application/json
     * 请求体：{"question": "Java如何入门？", "sessionId": "abc123"}
     * 
     * 响应格式：SSE (Server-Sent Events)
     * 响应示例：
     *   data: {"eventData":"您好","eventType":1001}
     *   data: {"eventData":"！","eventType":1001}
     *   data: {"eventType":1002}
     */
    @NoWrapper  // 关键注解：绕过统一响应包装
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO){
        return chatService.chat(chatDTO.getQuestion(), chatDTO.getSessionId());
    }

    /**
     * 停止聊天
     * 应用场景：用户在 AI 回复过程中点击"停止生成"按钮
     * 实现原理：清除生成状态标记，触发 takeWhile 停止流
     */
    @PostMapping("/stop")
    public void stop(@RequestParam("sessionId") String sessionId) {
        chatService.stop(sessionId);
    }
}
```

#### 为什么使用 `@NoWrapper` 注解？

项目中有统一响应包装器 `WrapperResponseBodyAdvice`，会把所有 Controller 返回值包装成：
```json
{
    "code": 200,
    "msg": "OK",
    "data": <原始数据>,
    "requestId": "xxx"
}
```

**但 SSE 流式响应不能被包装**，因为：
1. 流是**多条消息**，不是一次响应，包装后前端无法按 SSE 协议解析
2. 包装后的 JSON 会破坏 `text/event-stream` Content-Type
3. 前端 `EventSource` API 要求纯文本格式

**`@NoWrapper` 的工作机制**：
```java
// WrapperResponseBodyAdvice.java
if(methodParameter.hasMethodAnnotation(NoWrapper.class)){
    return false;  // 检测到注解，不包装，直接返回原始 Flux 流
}
```

#### 为什么使用 `TEXT_EVENT_STREAM_VALUE`？

设置响应 `Content-Type` 为 `text/event-stream`，这是 SSE 协议的标准格式。前端通过 `EventSource` API 接收：
```javascript
const eventSource = new EventSource('/chat');
eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.eventType === 1001) {
        chatBox.append(data.eventData);  // 追加文本
    } else if (data.eventType === 1002) {
        eventSource.close();  // 关闭连接
    }
};
```

---

### 2.2 数据传输对象 — ChatDTO

```java
@Data
@Builder
public class ChatDTO {
    private String question;    // 用户的问题内容
    private String sessionId;   // 会话 ID（关联多轮对话）
}
```

**为什么用 DTO 而不是直接参数？**
- **便于扩展**：未来可能需要增加 `userId`、`modelType` 等字段，DTO 更灵活
- **字段清晰**：接口文档和 Swagger 展示更直观
- **解耦**：Controller 和 Service 层之间用 DTO 传递，互不干扰

---

### 2.3 核心业务实现 — ChatServiceImpl（重点）

```java
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;              // Spring AI 客户端
    private final SystemPromptConfig systemPromptConfig;  // 系统提示词配置

    // 存储大模型生成状态：sessionId -> true/false/null
    // true = 正在生成，false/null = 停止
    private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

    /**
     * 流式聊天：提交用户问题，持续推送 AI 回复片段
     * 
     * 完整执行流程见下方逐步解析
     */
    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {

        // 步骤 1：生成对话 ID
        String conversationId = ChatService.getConversationId(sessionId);

        return chatClient.prompt()

                // 步骤 2：注入系统提示词和当前时间
                .system(promptSystem -> promptSystem
                        .text(systemPromptConfig.getChatSystemMessage().get())
                        .param("now", DateUtil.now()))

                // 步骤 3：注入多轮对话记忆 ID
                .advisors(advisor -> advisor.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                        conversationId))

                // 步骤 4：设置用户问题
                .user(question)

                // 步骤 5：开启流式输出
                .stream()

                // 步骤 6：获取 ChatResponse 流
                .chatResponse()

                // 步骤 7：Reactor 流式处理

                // 7.1 请求大模型前，标记该会话正在生成
                .doFirst(() -> {
                    GENERATE_STATUS.put(sessionId, true);
                })

                // 7.2 大模型输出完成，清除生成状态
                .doOnComplete(() -> {
                    GENERATE_STATUS.remove(sessionId);
                })

                // 7.3 大模型输出异常，清除生成状态
                .doOnError(throwable -> {
                    GENERATE_STATUS.remove(sessionId);
                })

                // 7.4 根据状态控制是否继续输出
                .takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))

                // 7.5 转换为前端可消费的 DATA 事件
                .map(chatResponse -> {
                    String content = chatResponse.getResult().getOutput().getText();
                    return ChatEventVO.builder()
                            .eventData(content)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })

                // 7.6 追加 STOP 事件通知流结束
                .concatWith(Flux.just(ChatEventVO.builder()
                        .eventType(ChatEventTypeEnum.STOP.getValue())
                        .build()));
    }

    @Override
    public void stop(String sessionId) {
        GENERATE_STATUS.remove(sessionId);
    }
}
```

#### 逐步骤详细解析

##### 步骤 1：生成对话 ID
```java
String conversationId = ChatService.getConversationId(sessionId);
// getConversationId 实现：
// return UserContext.getUser() + "_" + sessionId;
```

**格式示例**：用户 ID=1，会话 ID=abc → `"1_abc"`

**为什么这样设计**：
- **多用户隔离**：不同用户的相同 sessionId 在多轮对话记忆中互不干扰
- **Redis Key 唯一**：作为 RedisChatMemory 的 key，保证每个用户每个会话独立存储
- **格式可读**：便于日志排查时快速识别用户和会话关系

---

##### 步骤 2：注入系统提示词和当前时间
```java
.system(promptSystem -> promptSystem
        .text(systemPromptConfig.getChatSystemMessage().get())
        .param("now", DateUtil.now()))
```

**系统提示词模板**（存储在 Nacos）：
```
你是一个智能学习助手，当前时间是：{{now}}
你可以回答课程、学习、职业规划等各类问题。
回答时请保持友好、耐心的态度。
```

**最终发送给 AI 的内容**（占位符被替换）：
```
你是一个智能学习助手，当前时间是：2026-07-04 15:30:00
你可以回答课程、学习、职业规划等各类问题。
回答时请保持友好、耐心的态度。
```

**为什么需要当前时间**：
- AI 模型本身不知道"现在是什么时候"，可能给出过时的建议
- 动态注入时间后，AI 可以给出时间相关的准确回答（如"今天的课程安排是..."）

---

##### 步骤 3：注入多轮对话记忆 ID
```java
.advisors(advisor -> advisor.param(
        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
        conversationId))
```

**工作原理**：

```
第一次请求：
  conversationId = "1_abc123"
  → MessageChatMemoryAdvisor 检测到此参数
  → 调用 RedisChatMemory.get("1_abc123", lastN=20)
  → Redis 返回空 List（首次无历史）
  → 只发送当前问题给 AI
  → AI 回答后，add("1_abc123", [用户问题, AI回答])

第二次请求：
  conversationId = "1_abc123"
  → get("1_abc123", lastN=20) 返回最近 20 条历史
  → 拼接历史 + 当前问题发送给 AI
  → AI 读取历史后，理解上下文连贯回答
  → 循环往复...
```

---

##### 步骤 4-6：设置问题、开启流、获取响应流
```java
.user(question)    // 设置用户问题
.stream()          // 开启流式输出模式
.chatResponse()    // 返回 Flux<ChatResponse>（包含元数据）
```

**`.stream()` vs `.call()`**：
- `.call()`：等待完整响应后一次性返回（阻塞）
- `.stream()`：逐 chunk 返回，实时性好，用户体验佳

**ChatResponse 结构**：
```json
{
    "result": {
        "output": {
            "text": "您好"  // AI 回复的一个片段
        },
        "metadata": {...}
    },
    "metadata": {...}
}
```

大模型输出一段长文本时，会被拆分成多个 chunk：
```
chunk1: "您好"
chunk2: "！"
chunk3: "我是"
chunk4: "您的"
chunk5: "AI"
chunk6: "助手"
...
```

---

##### 步骤 7：Reactor 流式处理

这是整个链路最核心也最复杂的部分。

###### 7.1 doFirst：标记生成开始
```java
.doFirst(() -> {
    GENERATE_STATUS.put(sessionId, true);
})
```

**时机**：在请求大模型之前执行

**作用**：
- 将 `sessionId → true` 存入 Map，表示"该会话正在生成 AI 回复"
- 为后续 `takeWhile` 判断提供依据

**为什么用 `doFirst`**：
- `doFirst` 在订阅（subscribe）时执行，早于任何数据元素
- 比其他钩子方法更早触发，确保状态第一时间标记

---

###### 7.2-7.3 doOnComplete / doOnError：清除状态
```java
.doOnComplete(() -> {
    GENERATE_STATUS.remove(sessionId);
})
.doOnError(throwable -> {
    GENERATE_STATUS.remove(sessionId);
});
```

**doOnComplete 触发时机**：大模型输出完成（正常结束）

**doOnError 触发时机**：调用大模型时发生异常（网络超时、模型错误等）

**为什么分两处清除**：
- 无论成功还是失败，都必须清除状态
- 否则 Map 会持续增长，导致内存泄漏

---

###### 7.4 takeWhile：控制流是否继续
```java
.takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
```

**作用**：只要状态为 `true`，就继续推送数据；状态被移除（null/false）则立即停止流

**完整控制流程**：
```
用户发送问题
    ↓
doFirst: GENERATE_STATUS.put("session123", true)
    ↓
大模型输出 chunk1 → takeWhile(检查状态=true) → 推送给前端
    ↓
大模型输出 chunk2 → takeWhile(检查状态=true) → 推送给前端
    ↓
┌───────────────────────────────────────────────────────────┐
│ 用户点击"停止生成"按钮                                      │
│   → POST /chat/stop?sessionId=session123                   │
│   → stop() 方法：GENERATE_STATUS.remove("session123")      │
│   → Map 中 sessionId 对应的值变为 null                      │
└───────────────────────────────────────────────────────────┘
    ↓
大模型输出 chunk3 → takeWhile(检查状态=null→false) → 停止流
    ↓
chunk4、chunk5、... 不再输出
```

**为什么不用 `break` 或 `return`**：
- Reactor 是响应式编程，不能用命令式的中断语句
- `takeWhile` 是响应式的"条件终止"操作符
- 它发出 `onComplete` 信号，让流正常结束而非异常中断

---

###### 7.5 map：转换为前端事件
```java
.map(chatResponse -> {
    String content = chatResponse.getResult().getOutput().getText();
    return ChatEventVO.builder()
            .eventData(content)
            .eventType(ChatEventTypeEnum.DATA.getValue())  // 1001
            .build();
})
```

**作用**：将 Spring AI 的 `ChatResponse` 转换为前端可消费的 `ChatEventVO`

**为什么需要转换**：
- `ChatResponse` 是 Spring AI 内部对象，包含大量元数据，前端不需要
- `ChatEventVO` 是精简的业务对象，只包含 `eventData`（文本）和 `eventType`（类型）

---

###### 7.6 concatWith：追加停止事件
```java
.concatWith(Flux.just(ChatEventVO.builder()
        .eventType(ChatEventTypeEnum.STOP.getValue())  // 1002
        .build()));
```

**作用**：在流尾部追加一条 STOP 事件

**SSE 流最终输出格式**：
```
data: {"eventData":"您好","eventType":1001}

data: {"eventData":"！我是","eventType":1001}

data: {"eventData":"AI助手","eventType":1001}

data: {"eventType":1002}

```

**前端处理逻辑**：
```javascript
eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.eventType === 1001) {
        chatBox.append(data.eventData);  // 追加文本到对话框
    } else if (data.eventType === 1002) {
        eventSource.close();  // 关闭 SSE 连接
        showMessage("生成完成");
    }
};
```

---

#### 为什么使用 ConcurrentHashMap？

```java
private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();
```

| 方案 | 问题 |
|------|------|
| `HashMap` | 多线程不安全，用户 A 和 B 同时聊天可能覆盖状态 |
| `HashTable` | 全表锁，性能差 |
| `ConcurrentHashMap` ✅ | 分段锁，线程安全且高性能 |

**static 修饰的原因**：
- 全局共享同一份状态，所有 Service 实例操作同一个 Map
- 如果不用 static，每个 Spring Bean 实例会有独立的 Map，stop 方法可能操作的是另一个实例的 Map

---

#### 分布式环境的局限性

```
当前实现：
  请求 → 实例A → ConcurrentHashMap（仅实例A内存）
                     ↑
  请求 → 实例B → ConcurrentHashMap（实例B看不到实例A的状态）
```

**问题**：如果负载均衡把"停止生成"请求分发到另一个实例，状态不同步

**优化方案**：改用 Redis
```java
// 标记开始
redisTemplate.opsForValue().set("generate:" + sessionId, "true", 5, TimeUnit.MINUTES);

// 检查状态
Boolean isGenerating = redisTemplate.opsForValue().get("generate:" + sessionId);

// 停止
redisTemplate.delete("generate:" + sessionId);
```

---

## 🔄 链路三：系统提示词与配置

### 3.1 系统提示词配置 — SystemPromptConfig

```java
@Slf4j
@Configuration
public class SystemPromptConfig {

    private final NacosConfigManager nacosConfigManager;  // Nacos 配置管理器
    private final AIProperties aiProperties;              // Nacos 连接配置

    /**
     * 系统提示词的原子引用
     * 使用 AtomicReference 而非 String 的原因：
     * 1. 多线程可见性（volatile 语义）
     * 2. 原子更新（get/set 原子性）
     * 3. 监听线程更新时，主线程读到的是完整的新值或旧值，不会半写状态
     */
    private final AtomicReference<String> chatSystemMessage = new AtomicReference<>();

    /**
     * 应用初始化方法
     * 执行时机：Spring 容器启动后，Bean 注入完成
     */
    @PostConstruct
    public void init() {
        loadConfig(aiProperties.getSystem().getChat(), chatSystemMessage);
    }

    private void loadConfig(AIProperties.System.Chat chatConfig, AtomicReference<String> target) {
        try {
            String dataId = chatConfig.getDataId();    // 配置 ID
            String group = chatConfig.getGroup();      // 配置分组
            long timeoutMs = chatConfig.getTimeoutMs(); // 拉取超时

            // 1. 同步拉取初始配置
            String config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            target.set(config);

            // 2. 注册配置变更监听器（热更新核心）
            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;  // 使用 Nacos 默认线程池
                }

                @Override
                public void receiveConfigInfo(String info) {
                    // Nacos 推送新配置时触发
                    target.set(info);
                    log.info("系统提示词已更新");
                }
            });
        } catch (Exception e) {
            // 异常不抛出，保证应用启动不受影响
            log.error("加载配置失败", e);
        }
    }
}
```

#### 工作流程
```
应用启动
    ↓
@PostConstruct 触发 init()
    ↓
loadConfig() 从 Nacos 拉取初始配置
    ↓
注册配置变更监听器
    ↓
等待配置变更...

───────────────────────────────────────────

Nacos 中修改配置
    ↓
Nacos 推送新配置到应用
    ↓
receiveConfigInfo() 被调用
    ↓
target.set(新配置)
    ↓
下一次聊天请求读取新配置
```

#### 业务价值
| 价值 | 说明 |
|------|------|
| **动态调整 AI 人设** | 在 Nacos 修改提示词，AI 风格即时变化 |
| **无需重启服务** | 修改配置后零停机生效 |
| **多环境隔离** | 开发/测试/生产不同配置 |
| **热更新封装** | 后续可扩展到其他配置（如模型参数） |

---

### 3.2 Nacos 连接配置 — AIProperties

```java
@ConfigurationProperties(prefix = "tj.ai.prompt")
@Data
public class AIProperties {
    private System system;

    @Data
    public static class System {
        private Chat chat;

        @Data
        public static class Chat {
            private String dataId;           // 配置 ID（必填）
            private String group = "DEFAULT_GROUP";  // 分组（默认）
            private long timeoutMs = 20000L;  // 拉取超时（默认 20 秒）
        }
    }
}
```

**对应 Nacos 配置**：
```yaml
tj:
  ai:
    prompt:
      system:
        chat:
          dataId: "tj-aigc-system-prompt"  // Nacos 中的配置 ID
          group: "DEFAULT_GROUP"
          timeoutMs: 20000
```

**为什么使用嵌套类结构**：
- 镜像配置文件层级，结构清晰
- 便于扩展：未来可能添加 `user.prompt.business.chat` 等配置
- 类型安全：编译期检查字段名，避免运行时错误

---

### 3.3 Spring AI 客户端配置 — SpringAIConfig

```java
@Configuration
public class SpringAIConfig {

    /**
     * 创建并配置 ChatClient（Spring AI 核心聊天客户端）
     * 
     * @param chatClientBuilder 由 Spring AI 自动配置的构建器
     *                         包含 API 密钥、模型端点等基础配置
     * @param loggerAdvisor 日志记录顾问器
     * @param messageChatMemoryAdvisor 聊天记忆顾问器
     * @return 全局唯一的 ChatClient 实例
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor loggerAdvisor,
                                 Advisor messageChatMemoryAdvisor) {
        return chatClientBuilder
                .defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor)
                .build();
    }

    /**
     * 日志记录器
     * 自动记录所有请求/响应的详细信息
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * 基于 Redis 的会话记忆
     */
    @Bean
    public ChatMemory chatMemory() {
        return new RedisChatMemory();
    }

    /**
     * 基于 Redis 的会话记忆顾问器
     * 负责自动管理对话历史记录的存储和检索
     * 
     * 工作原理：
     * 1. 请求前：从 ChatMemory 获取历史对话
     * 2. 将历史对话整合到 system message 中
     * 3. 响应后：保存当前轮对话到 ChatMemory
     */
    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return new MessageChatMemoryAdvisor(chatMemory);
    }
}
```

#### 顾问器（Advisor）链
```
每次 ChatClient 调用
    ↓
SimpleLoggerAdvisor：记录请求日志
    ↓
MessageChatMemoryAdvisor：获取/保存多轮对话记忆
    ↓
调用大模型 API
    ↓
返回响应

#### 为什么使用顾问器模式？
- **解耦**：日志、记忆、重试等功能独立封装，互不干扰
- **可组合**：按需添加/移除顾问器，灵活配置
- **AOP 思想**：在核心调用前后插入横切关注点，业务代码无感知

---

## 🔄 链路四：多轮对话记忆存储

### 4.1 记忆接口 — ChatMemory

```java
public interface ChatMemory {

    /**
     * 便捷方法：添加单条消息
     * 
     * 为什么使用 default 方法？
     * - 提供便利，实现类无需重写单条添加逻辑
     * - 自动包装为 List 调用批量方法
     * - 符合 Java 8+ 接口设计最佳实践
     */
    default void add(String conversationId, Message message) {
        this.add(conversationId, List.of(message));
    }

    /**
     * 批量添加消息（核心方法）
     * 实现类必须提供此方法的线程安全实现
     */
    void add(String conversationId, List<Message> messages);

    /**
     * 获取最近 lastN 条消息
     * 用于构建多轮对话上下文
     */
    List<Message> get(String conversationId, int lastN);

    /**
     * 清除指定会话的所有记忆
     */
    void clear(String conversationId);
}
```

---

### 4.2 Redis 实现 — RedisChatMemory

```java
public class RedisChatMemory implements ChatMemory {

    public static final String DEFAULT_PREFIX = "CHAT:";

    private final String prefix;           // Key 前缀，支持环境隔离
    private StringRedisTemplate stringRedisTemplate;  // Redis 操作模板

    /**
     * 添加消息到会话
     * 
     * Redis 命令：RPUSH CHAT:123 '{"role":"user","content":"你好"}'
     * 
     * 执行流程：
     * 1. 空消息列表防御（避免无效 Redis 操作）
     * 2. 生成 Key：prefix + conversationId
     * 3. 绑定 List 操作器
     * 4. 遍历消息，序列化后追加到 List 右侧
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return;  // 防御：空消息不操作 Redis
        }
        
        String redisKey = getKey(conversationId);
        BoundListOperations<String, String> listOps = stringRedisTemplate.boundListOps(redisKey);
        
        // 遍历消息，每条 Message 转为 JSON 后追加到 List 尾部
        messages.forEach(message -> {
            String jsonMessage = JSONUtil.toJsonStr(message);
            listOps.rightPush(jsonMessage);
        });
    }

    /**
     * 获取最近 lastN 条消息
     * 
     * Redis 命令：LRANGE CHAT:123 -10 -1
     * 
     * 执行流程：
     * 1. 防御：lastN <= 0 返回空列表
     * 2. LRANGE 获取最近 N 条（负数索引从尾部开始）
     * 3. 防御：Key 不存在返回空列表
     * 4. JSON 反序列化：String → Message
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return List.of();
        }
        
        String redisKey = getKey(conversationId);
        
        // LRANGE key -lastN -1：获取最后 lastN 条
        // 示例：List 有 100 条，lastN=10 → 获取第 91-100 条
        List<String> jsonMessages = stringRedisTemplate.boundListOps(redisKey)
                .range(-lastN, -1);
        
        if (CollUtil.isEmpty(jsonMessages)) {
            return List.of();
        }
        
        // JSON 反序列化
        return jsonMessages.stream()
                .map(json -> JSONUtil.toBean(json, Message.class))
                .collect(Collectors.toList());
    }

    /**
     * 清除会话
     * Redis 命令：DEL CHAT:123
     */
    @Override
    public void clear(String conversationId) {
        String redisKey = getKey(conversationId);
        stringRedisTemplate.delete(redisKey);
    }

    private String getKey(String conversationId) {
        return prefix + conversationId;
    }
}
```

#### Redis 数据结构设计

```
Key: CHAT:1_abc123     (prefix=CHAT:, conversationId=1_abc123)
Value: List<String>

┌─────────────────────────────────────────────────────────────────┐
│  头部（最旧）                                          尾部（最新）│
│       ←                                                          →│
│  [msg1, msg2, msg3, ..., msg98, msg99, msg100]               │
│                                                                 │
│  LRANGE key -10 -1  →  获取 msg91~msg100（最近 10 条）         │
└─────────────────────────────────────────────────────────────────┘

message JSON 示例：
{"role":"user","content":"你好"}
{"role":"assistant","content":"您好！"}
```

#### 为什么选择 Redis List 结构？

| 数据结构 | 优点 | 缺点 | 适用场景 |
|----------|------|------|----------|
| **List** ✅ | 有序、支持两端操作、滑动窗口查询 | 查询中间元素慢 O(N) | 消息队列、时间线 |
| Set | 去重、无序 | 不保证顺序 | 标签、好友 |
| Hash | 字段级 CRUD | 不支持范围查询 | 对象存储 |
| ZSet | 有序、按分数排序 | 实现复杂 | 排行榜 |

List 的 `RPUSH`（追加）和 `LRANGE`（滑动窗口）完美契合聊天消息场景。

---

### 4.3 同步阻塞 vs 异步非阻塞的思考

当前 `ChatMemory` 接口是同步阻塞的：
```java
void add(String conversationId, List<Message> messages);
List<Message> get(String conversationId, int lastN);
```

**在 Reactor 流式场景下的问题**：
```
流式输出流程（Flux）
    │
    ├── 阻塞！Redis.get()  ← 占用线程
    │
    ├── AI 生成 chunk1
    │
    ├── 阻塞！Redis.add()  ← 占用线程
    │
    └── Flux 继续输出...
```

阻塞调用会占用线程池资源，高并发时降低系统吞吐量。

**理想方案**：异步非阻塞版本
```java
Mono<Void> addAsync(String conversationId, List<Message> messages);
Flux<Message> getAsync(String conversationId, int lastN);
Mono<Void> clearAsync(String conversationId);
```

**为什么目前没实现**：
- Spring AI 的 `MessageChatMemoryAdvisor` 只支持阻塞接口
- 需要等上游框架适配或自己封装 Reactive 版本

---

## 🔄 链路五：响应事件类型

### 5.1 事件类型枚举 — ChatEventTypeEnum

```java
@Getter
public enum ChatEventTypeEnum implements BaseEnum {

    /**
     * 数据事件：携带 AI 回复的文本片段
     * 前端处理：将 eventData 追加到对话展示区域
     */
    DATA(1001, "数据事件"),

    /**
     * 停止事件：通知前端本轮对话已结束
     * 前端处理：关闭 SSE 连接，更新 UI 状态
     */
    STOP(1002, "停止事件"),

    /**
     * 参数事件（预留）：用于动态调整 AI 行为参数
     * 前端展示示例：显示"正在调整回复风格为正式"
     */
    PARAM(1003, "参数事件");

    private final int value;
    private final String desc;
}
```

| 事件类型 | eventType | eventData | 前端处理 |
|----------|-----------|-----------|----------|
| DATA | 1001 | String（文本片段） | 追加到聊天框 |
| STOP | 1002 | null | 关闭 SSE 连接 |
| PARAM | 1003 | 预留 | 预留扩展 |

---

### 5.2 事件值对象 — ChatEventVO

```java
@Data
@Builder
public class ChatEventVO {
    private Object eventData;   // DATA 事件时：AI 回复文本；STOP 事件时：null
    private int eventType;      // 事件类型标识
}
```

**为什么 eventType 用 int 而非 Enum**：
- Enum 在 JSON 序列化时需要特殊处理（`@JsonValue`）
- int 类型更通用，便于前端判断和日志分析
- 枚举类中已有 `getValue()` 方法提供转换

---

## 🎯 设计亮点总结

| 设计点 | 实现方式 | 价值 |
|--------|----------|------|
| **流式响应** | Spring AI + Reactor Flux | 实时逐字输出，用户体验丝滑 |
| **SSE 绕过包装** | `@NoWrapper` 注解 | 兼容流式协议和统一响应格式 |
| **停止生成** | `ConcurrentHashMap` + `takeWhile` | 用户可随时中断，节省资源 |
| **多轮对话** | RedisChatMemory + conversationId | 理解上下文，智能对话 |
| **动态提示词** | Nacos 配置 + 热更新 | 运营实时调整 AI 人设 |
| **线程安全** | ConcurrentHashMap + AtomicReference | 多用户并发安全 |
| **事件类型** | DATA/STOP/PARAM 枚举 | 扩展性强，前端易处理 |
| **防御性编程** | 空值校验、异常捕获 | 生产环境稳定运行 |
| **配置驱动** | @ConfigurationProperties | 动态更新，解耦硬编码 |

---

## ⚠️ 潜在优化建议

### 1. 分布式环境支持
```java
// 当前：仅支持单机内存
private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

// 优化：改用 Redis 支持分布式
private void setGenerating(String sessionId, boolean generating) {
    redisTemplate.opsForValue().set("generate:" + sessionId, generating, 5, TimeUnit.MINUTES);
}
```

### 2. 会话记忆过期时间
```java
@Override
public void add(String conversationId, List<Message> messages) {
    // ... existing code ...
    // 优化：设置 7 天过期，自动清理历史会话
    stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
}
```

### 3. 超时控制
```java
// 添加流超时控制，防止长时间挂起
.timeout(Duration.ofSeconds(60))
```

### 4. 限流保护
```java
// 防止单用户频繁请求
@RateLimiter(key = "#userId", rate = 10, interval = 60)
```

---

## 📊 完整请求时序图

```
客户端                Controller              Service              Spring AI           Redis/Nacos
   │                     │                     │                     │                    │
   │  POST /session?n=3  │                     │                     │                    │
   │────────────────────>│                     │                     │                    │
   │                     │ createSession(num=3) │                     │                    │
   │                     │────────────────────>│                     │                    │
   │                     │                     │ 读取 SessionProperties │                    │
   │                     │                     │ (配置中心获取)        │                    │
   │                     │                     │                     │                    │
   │                     │                     │ 随机选取热门话题        │                    │
   │                     │                     │ 生成 sessionId (UUID) │                    │
   │                     │                     │                     │                    │
   │                     │                     │ 保存 ChatSession 到 MySQL                │
   │                     │                     │─────────────────────────────────────────>│
   │                     │                     │                     │                    │
   │                     │<─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                     │                    │
   │<─ ─ SessionVO ─ ─ ─ │                     │                     │                    │
   │                     │                     │                     │                    │
   │                     │                     │                     │                    │
   │  POST /chat         │                     │                     │                    │
   │  {question, sessionId}                    │                     │                    │
   │────────────────────>│                     │                     │                    │
   │                     │ chat(question, sessionId)                  │                    │
   │                     │────────────────────>│                     │                    │
   │                     │                     │                     │                    │
   │                     │                     │ 生成 conversationId  │                    │
   │                     │                     │ (用户ID_会话ID)      │                    │
   │                     │                     │                     │                    │
   │                     │                     │ 读取系统提示词        │                    │
   │                     │                     │────────────────────────────────────────>│
   │                     │                     │<─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                    │
   │                     │                     │                     │                    │
   │                     │                     │ 获取多轮对话历史      │                    │
   │                     │                     │─────────────────────────────────>│       │
   │                     │                     │<─ ─ 历史消息列表 ─ ─ │                    │
   │                     │                     │                     │                    │
   │                     │                     │ 调用 ChatClient      │                    │
   │                     │                     │ (系统提示词+历史+问题) │                    │
   │                     │                     │────────────────────>│                    │
   │                     │                     │                     │                    │
   │                     │                     │                     │ 调用大模型 API      │
   │                     │                     │                     │──────────────────> │
   │                     │                     │                     │                    │
   │  SSE 流开始         │                     │                     │ 流式返回 AI 回复    │
   │<─ ─ ─ ─ ─ ─ ─ ─ ─ │<─ ─ ─ ─ ─ ─ ─ ─ ─ │<─ ─ ─ ─ ─ ─ ─ ─ ─ │<─ ─ ─ ─ ─ ─ ─ ─ │
   │                     │                     │                     │                    │
   │ chunk1: "您好"      │                     │                     │                    │
   │<─ ─ ─ ─ ─ ─ ─ ─ ─ │                     │                     │                    │
   │                     │                     │                     │                    │
   │ chunk2: "！"        │                     │                     │                    │
   │<─ ─ ─ ─ ─ ─ ─ ─ ─ │                     │                     │                    │
   │                     │                     │                     │                    │
   │ chunk3: "我是"      │                     │                     │                    │
   │<─ ─ ─ ─ ─ ─ ─ ─ ─ │                     │                     │                    │
   │                     │                     │                     │                    │
   │ ...                 │                     │                     │                    │
   │                     │                     │                     │                    │
   │ 保存对话到 Redis     │                     │                     │                    │
   │                     │                     │─────────────────────────────────>│       │
   │                     │                     │                     │                    │
   │ SSE 流结束          │                     │                     │                    │
   │<─ ─ STOP 事件 ─ ─ ─ │                     │                     │                    │
   │                     │                     │                     │                    │
```

---

> 文档版本：v1.0
> 
> 最后更新：2026-07-04
> 
> 维护者：Natural Pride
