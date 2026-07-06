# TianJIAI AI智能客服微服务 - 架构完整解析

## 一、系统整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           前端 (Vue/React)                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  会话列表    │  │  聊天窗口    │  │  课程卡片    │  │  预下单确认卡片   │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                              │ HTTP / SSE
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ChatController (入口层)                               │
│  POST /chat ────────────────────────────────────────────────────────────── │
│  POST /chat/stop ───────────────────────────────────────────────────────── │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ChatServiceImpl (核心业务层)                               │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  核心流程：                                                             │ │
│  │  1. 构建 conversationId (userId_sessionId)                              │ │
│  │  2. 生成 requestId，存入 ToolResultHolder                              │ │
│  │  3. 调用 ChatClient → 获取流式响应                                      │ │
│  │  4. 处理工具调用结果 → 返回 PARAM 事件                                 │ │
│  │  5. 返回 STOP 事件 → 结束流                                           │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│   ChatClient    │ │  VectorStore     │ │  ToolResultHolder │
│  (Spring AI)    │ │  (知识库RAG)    │ │  (工具结果暂存)  │
└─────────────────┘ └─────────────────┘ └─────────────────┘
          │                  │                  │
          ▼                  ▼                  ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  大模型 (LLM)   │ │  向量数据库     │ │  CourseTools     │
│  工具调用决策    │ │  (相似度搜索)   │ │  OrderTools     │
└─────────────────┘ └─────────────────┘ └─────────────────┘
```

## 二、核心功能模块详解

### 1. 会话管理 (Session Management)

**核心组件**：`ChatSessionService`、`RedisChatMemory`

**工作原理**：
```
用户发起会话 → 生成 sessionId → 创建 ChatSession 记录
                                    ↓
                  Redis 存储会话元信息（标题、用户ID、创建时间）
                                    ↓
                  后续对话使用 sessionId 作为 conversationId 的一部分
```

**conversationId 格式**：`{userId}_{sessionId}`
- 示例：`1_abc123def456`
- 作用：实现多用户多会话的对话记忆隔离

---

### 2. 流式对话 (Streaming Chat)

**核心组件**：`ChatController` → `ChatServiceImpl` → `ChatClient`

**SSE 事件流**：
```
前端请求：POST /chat {"question": "Java课程多少钱？", "sessionId": "abc123"}

后端返回 (SSE)：
data: {"eventData":"根据查询，","eventType":1001}
data: {"eventData":"Java基础课程的价格是","eventType":1001}
data: {"eventData":" 299 元。","eventType":1001}
data: {"eventData":{"courseInfo":{...}},"eventType":1003}  ← 工具调用结果
data: {"eventType":1002}  ← 结束标记
```

**事件类型枚举**：
| 事件类型 | 值 | 说明 |
|---------|-----|------|
| DATA | 1001 | AI 回复文本片段 |
| STOP | 1002 | 流结束标志 |
| PARAM | 1003 | 工具调用结果参数 |

---

### 3. 系统提示词 (System Prompt)

**核心组件**：`SystemPromptConfig` + Nacos 配置中心

**工作流程**：
```
1. 应用启动 → 从 Nacos 拉取系统提示词配置
2. 注册配置监听器 → 配置变更时自动热更新
3. 每次请求 → 注入当前时间 {{now}} 等占位符
4. 发送给大模型 → 定义 AI 角色和行为规范
```

**提示词示例**：
```
你是一名专业的课程顾问，负责为用户推荐课程。
当前时间：{{now}}
请根据用户问题，提供准确、有帮助的回答。
如果用户询问课程信息，请调用工具查询。
```

---

### 4. 会话记忆 (Chat Memory)

**核心组件**：`RedisChatMemory` + `MessageChatMemoryAdvisor`

**Redis 数据结构设计**：
```
Key: CHAT:{conversationId}
     ┌──────────────────────────────────────────────────┐
     │  [0] {"messageType":"USER","textContent":"你好"}      │ ← 最早消息
     │  [1] {"messageType":"ASSISTANT","textContent":"您好！"} │
     │  [2] {"messageType":"USER","textContent":"推荐课程"}   │
     │  [3] {"messageType":"ASSISTANT","textContent":"..."}    │ ← 最新消息
     └──────────────────────────────────────────────────┘
```

**多轮对话实现**：
```
第1轮：用户问"推荐Java课程" → AI回答 → 保存到 Redis
第2轮：用户问"多少钱" → 从Redis获取历史 → AI理解上下文 → 回答价格
```

---

### 5. 课程查询功能 (Course Query) ⭐ 重点

**核心组件**：`CourseTools` → `CourseClient` → 课程微服务

**完整调用链路**：
```
用户："Java基础课程多少钱？"
         ↓
大模型识别需要查询课程 → 决定调用 CourseTools.queryCourseById
         ↓
CourseTools.queryCourseById(courseId, toolContext)
         │
         ├── 1. 调用 CourseClient.baseInfo(courseId, true) → 获取课程详情
         │
         ├── 2. 转换为 CourseInfo 对象（价格分转元、格式化）
         │
         ├── 3. 存入 ToolResultHolder：
         │      key = requestId
         │      field = "courseInfo_{courseId}"
         │      value = CourseInfo 对象
         │
         └── 4. 返回 CourseInfo 给大模型
         ↓
大模型根据课程信息生成回答 → 流式返回给用户
         ↓
ChatServiceImpl 检测到 ToolResultHolder 有数据
         ↓
返回 PARAM 事件 (eventType=1003) → 前端接收课程卡片数据
         ↓
前端渲染课程卡片组件
```

**课程卡片数据结构**：
```json
{
  "id": 123,
  "name": "Java基础课程",
  "price": 299.00,
  "validDuration": 12,
  "usePeople": "初学者",
  "detail": "适合零基础学员的Java入门课程..."
}
```

**前端课程卡片渲染**：
```
根据 PARAM 事件中的 courseInfo 数据
         ↓
渲染为可视化卡片（包含课程名、价格、有效期、适用人群）
         ↓
用户可点击卡片查看详情或直接购买
```

---

### 6. 预下单功能 (Pre-Order) ⭐ 重点

**核心组件**：`OrderTools` → `TradeClient` → 交易微服务

**完整调用链路**：
```
用户："我要购买Java课程"
         ↓
大模型识别需要预下单 → 决定调用 OrderTools.prePlaceOrder
         ↓
OrderTools.prePlaceOrder(courseIds, toolContext)
         │
         ├── 1. 从 toolContext 获取 userId，设置到 UserContext
         │
         ├── 2. 调用 TradeClient.prePlaceOrder(courseIds) → 获取预下单结果
         │
         ├── 3. 转换为 PrePlaceOrder 对象（计算优惠、实付金额）
         │
         ├── 4. 存入 ToolResultHolder：
         │      key = requestId
         │      field = "prePlaceOrder"
         │      value = PrePlaceOrder 对象
         │
         └── 5. 返回 PrePlaceOrder 给大模型
         ↓
大模型根据预下单结果生成回答 → 流式返回给用户
         ↓
ChatServiceImpl 检测到 ToolResultHolder 有数据
         ↓
返回 PARAM 事件 (eventType=1003) → 前端接收预下单数据
         ↓
前端渲染预下单确认卡片
```

**预下单卡片数据结构**：
```json
{
  "count": 2,
  "totalAmount": 598.00,
  "discountAmount": 50.00,
  "couponName": "单券：【新用户优惠】",
  "payAmount": 548.00,
  "courseIds": [123, 456],
  "orderId": 789,
  "couponId": 10
}
```

**前端预下单卡片渲染**：
```
根据 PARAM 事件中的 prePlaceOrder 数据
         ↓
渲染为订单确认卡片（课程数量、总金额、优惠、实付金额）
         ↓
用户确认 → 调用支付接口 → 完成下单
```

---

### 7. 知识库功能 (Knowledge Base / RAG)

**核心组件**：`EmbeddingController` + `VectorStore` + `QuestionAnswerAdvisor`

**知识库写入流程**：
```
运营人员准备知识文档（课程FAQ、常见问题、产品说明等）
         ↓
调用 POST /embedding {"messages": ["Java课程适合零基础吗？", ...]}
         ↓
EmbeddingController.saveVectorStore(messages)
         │
         ├── 1. 将文本转换为 Document 对象
         │
         ├── 2. 调用 EmbeddingModel 将文本向量化
         │
         └── 3. 存入向量数据库 (VectorStore)
         ↓
知识库数据准备就绪
```

**知识库检索流程 (RAG)**：
```
用户提问："Java课程适合零基础吗？"
         ↓
ChatServiceImpl.chat() 中配置了 QuestionAnswerAdvisor
         ↓
QuestionAnswerAdvisor 自动执行：
         │
         ├── 1. 将用户问题向量化
         │
         ├── 2. 在向量数据库中搜索相似文档 (topK=5, similarityThreshold=0.5)
         │
         ├── 3. 将相似文档作为上下文注入到 Prompt 中
         │
         └── 4. 大模型基于知识库内容生成回答
         ↓
返回准确的、基于知识库的回答
```

**向量搜索 API**：
```
GET /embedding/search?message=Java课程适合零基础吗？
返回：List<Document> 相似度最高的文档列表
```

---

## 三、工具调用结果传递机制 (核心难点)

### ToolResultHolder 工作原理

```
┌─────────────────────────────────────────────────────────────────┐
│                    ToolResultHolder (全局暂存)                    │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Map<requestId, Map<field, result>>                         ││
│  │                                                             ││
│  │  requestId_1: {                                             ││
│  │    "courseInfo_123": CourseInfo{...},                        ││
│  │    "prePlaceOrder": PrePlaceOrder{...}                       ││
│  │  }                                                          ││
│  │                                                             ││
│  │  requestId_2: {                                             ││
│  │    "courseInfo_456": CourseInfo{...}                         ││
│  │  }                                                          ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

**数据流转过程**：
```
1. ChatServiceImpl.chat() 开始
   └── 生成 requestId，存入 ToolResultHolder.put(conversationId, "requestId", requestId)

2. 大模型调用工具 (CourseTools/OrderTools)
   └── 工具内部：ToolResultHolder.put(requestId, field, result)

3. 流式输出结束 (concatWith)
   └── 从 ToolResultHolder.get(requestId) 获取所有工具结果
   └── 如果有数据，返回 PARAM 事件 (eventType=1003)
   └── 清理 ToolResultHolder.remove(requestId)

4. 前端接收 PARAM 事件
   └── 根据 eventData 中的数据渲染课程卡片或预下单卡片
```

---

## 四、完整请求流程串联

### 场景：用户询问课程并下单

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤1：前端发起聊天请求                                                      │
│ POST /chat {"question": "Java基础课程多少钱？我想购买", "sessionId": "abc123"}   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤2：ChatServiceImpl 处理请求                                              │
│ - 生成 conversationId = "1_abc123"                                          │
│ - 生成 requestId = "req-uuid-1"                                           │
│ - 存入 ToolResultHolder: conversationId → requestId 映射                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤3：调用 ChatClient，配置 Advisor                                         │
│ - system: 系统提示词 + 当前时间                                              │
│ - advisors:                                                                  │
│   ├─ QuestionAnswerAdvisor: 从知识库检索相关上下文                            │
│   └─ MessageChatMemoryAdvisor: 从 Redis 获取历史对话                         │
│ - user: 用户问题                                                            │
│ - toolContext: requestId, userId                                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤4：大模型决策调用工具                                                    │
│ - 识别需要查询课程 → 调用 CourseTools.queryCourseById(123)                  │
│   └─ 结果存入 ToolResultHolder: req-uuid-1 → "courseInfo_123" → CourseInfo  │
│ - 识别需要预下单 → 调用 OrderTools.prePlaceOrder([123])                        │
│   └─ 结果存入 ToolResultHolder: req-uuid-1 → "prePlaceOrder" → PrePlaceOrder│
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤5：流式处理响应                                                          │
│ - doFirst: 设置 GENERATE_STATUS[sessionId] = true                           │
│ - map: 将每个 ChatResponse 转换为 DATA 事件 (eventType=1001)                 │
│ - takeWhile: 检查 GENERATE_STATUS，支持停止生成                               │
│ - doOnComplete: 清除 GENERATE_STATUS                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤6：concatWith 处理工具结果                                              │
│ - 从 ToolResultHolder.get(requestId) 获取工具结果                            │
│ - 如果有数据：                                                              │
│   └─ 返回 PARAM 事件 (eventType=1003) + STOP 事件 (eventType=1002)          │
│ - 如果无数据：                                                              │
│   └─ 只返回 STOP 事件                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 步骤7：前端接收 SSE 事件流                                                  │
│ - DATA 事件: 逐段显示 AI 回复文本                                           │
│ - PARAM 事件: 渲染课程卡片和预订单卡片                                        │
│ - STOP 事件: 关闭 SSE 连接，显示生成完成                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 五、关键设计亮点

### 1. 工具结果暂存机制
- **问题**：工具调用在流式处理过程中完成，但结果需要在流结束后返回给前端
- **解决**：使用 `ToolResultHolder` 全局暂存，以 requestId 为 key 存储工具结果
- **优势**：解耦工具调用和结果返回，支持多个工具并行调用

### 2. 知识库 RAG 集成
- **QuestionAnswerAdvisor**：自动从向量数据库检索相似文档
- **配置参数**：topK=5, similarityThreshold=0.5
- **效果**：大模型基于知识库内容生成准确回答，避免幻觉

### 3. 会话记忆持久化
- **Redis 存储**：支持服务重启后恢复会话历史
- **多实例部署**：多服务共享 Redis，会话状态同步
- **滑动窗口**：获取最近 N 条消息，控制上下文长度

### 4. 流式响应与停止生成
- **Reactor Flux**：响应式编程，实时推送 AI 回复片段
- **停止生成**：通过 GENERATE_STATUS Map 控制，用户可随时中断

---

## 六、学习建议

### 理解顺序建议
1. **先理解基础流程**：会话管理 → 流式对话 → 系统提示词 → 会话记忆
2. **再理解工具调用**：课程查询 → 预下单 → 工具结果传递机制
3. **最后理解高级功能**：知识库 RAG → 向量搜索 → 完整流程串联

### 调试建议
1. **查看 SSE 事件流**：浏览器 DevTools → Network → EventStream
2. **查看 Redis 数据**：`redis-cli` → `LRANGE CHAT:1_abc123 0 -1`
3. **查看日志**：关注 `ChatServiceImpl` 和 `CourseTools` 的日志输出

### 常见问题
1. **工具结果未返回**：检查 ToolResultHolder 的 requestId 是否匹配
2. **知识库未生效**：检查 QuestionAnswerAdvisor 配置和向量数据库数据
3. **会话记忆丢失**：检查 Redis 连接和 conversationId 格式
