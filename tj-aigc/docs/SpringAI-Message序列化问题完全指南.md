# Spring AI Message 序列化问题完全指南

> 本文档基于 TianJIAI 项目（tj-aigc 模块）的实际代码，系统分析 Hutool JSONUtil 序列化 Spring AI Message 对象时**文本内容写入失败**的根因，并给出可复用的 **ChatMessageWrapper 包装类解决方案**。

---

## 一、问题描述

在需要将 Spring AI 的 `Message` 对象进行 JSON 序列化的场景（如持久化到 Redis、存入消息队列、跨进程传输等），直接使用 Hutool `JSONUtil` 会导致 **`getText()` 读取值为空**，不会抛异常，属于**静默失败**。

### 典型场景

```java
// 直接把 Message 存入 Redis
Message userMsg = new UserMessage("你好");
String json = JSONUtil.toJsonStr(userMsg);   // ← 问题：文本内容丢失
redisTemplate.opsForList().rightPush("CHAT:123", json);

// 读取还原
String read = redisTemplate.opsForList().index("CHAT:123", 0);
Message restored = JSONUtil.toBean(read, UserMessage.class);
System.out.println(restored.getText());       // ← 输出：null ❌
```

---

## 二、根因分析——为什么会出现这个问题

### 2.1 Hutool JSONUtil 的序列化/反序列化规则

Hutool 底层依赖 **JavaBean 命名约定** 识别对象的属性：

| 操作 | Hutool 规则 | 示例 |
|------|-------------|------|
| **序列化（写）** | 扫描 `getXxx()` / `isXxx()` → 识别属性名为 `xxx`（首字母小写） | `getUserName()` → 属性 `"userName"` |
| **反序列化（读）** | 根据 JSON key → 寻找 `setXxx()` → 调用设值 | `"age": 18` → 调用 `setAge(18)` |

### 2.2 Spring AI AbstractMessage 的实际结构

```java
package org.springframework.ai.chat.messages;

// Message 是一个接口
public interface Message {
    String getText();
    MessageType getMessageType();
    Map<String, Object> getMetadata();
}

// 实现类 AbstractMessage
public abstract class AbstractMessage implements Message {
    
    private String textContent;          // ← 字段名叫 textContent
    
    public String getText() {            // ← 但 getter 名叫 getText()，不是 getTextContent()
        return this.textContent;
    }
    // ⚠️ 没有 setText() 方法
    // ⚠️ 没有 getTextContent() 方法
}
```

### 2.3 三层不匹配叠加导致静默失败

```
┌────────────────────────────────────────────────────────────────────┐
│                 第一层：getter 名 vs 字段名 不匹配                     │
│                                                                    │
│   字段名：textContent         getter 名：getText()                    │
│   Hutool 扫描 getText() → 识别属性为 "text"（错！应该是 textContent）│
└────────────────────────────────────────────────────────────────────┘
                                ↓
┌────────────────────────────────────────────────────────────────────┐
│                 第二层：接口无法直接反序列化                          │
│                                                                    │
│   Message 是 interface，不是 class                                   │
│   Hutool toBean(json, Message.class) → ❌ 无法实例化               │
│   toBean(json, UserMessage.class) → 找到 "text" key                 │
│   → 寻找 setText() → 不存在 → 跳过 → textContent 永远为 null        │
└────────────────────────────────────────────────────────────────────┘
                                ↓
┌────────────────────────────────────────────────────────────────────┐
│                 第三层：类型信息丢失                                  │
│                                                                    │
│   即使解决了前两层，UserMessage / AssistantMessage / SystemMessage   │
│   是三个不同的实现类，JSON 中缺少类型标记，还原时不知道 new 谁        │
└────────────────────────────────────────────────────────────────────┘
```

### 2.4 序列化方向 vs 反序列化方向分开看

```
【序列化方向——写 JSON】

反射扫描 UserMessage 对象：
├── getMessageType() → 属性 "messageType" → {"messageType":"USER"}  ✅
├── getText()        → 属性 "text"        → {"text":"你好"}       ⚠️ key 错位
└── getMetadata()    → 属性 "metadata"    → {...}                   ✅

输出 JSON：{"messageType":"USER","text":"你好","metadata":{...}}
                              ↑
                        语义错位：理解成本高，和源码字段对不上
                        （不会抛异常，因此难以被发现）


【反序列化方向——读 JSON】

JSON 入参：  {"messageType":"USER","text":"你好","metadata":{...}}
Target 类：  UserMessage.class

Hutool 执行：
├── "messageType" → find setMessageType() → 存在 → 设值          ✅
├── "text"        → find setText()        → 不存在 → 跳过          ❌
│                        ↳ 即使字段名叫 "text"，UserMessage 也没有 setText()
└── "metadata"    → find setMetadata()    → 存在 → 设值          ✅

结果：textContent 字段 = null → getText() 返回 null             ❌❌❌
```

---

## 三、解决方案——ChatMessageWrapper 包装类

### 3.1 设计思路

```
【核心思想】不直接序列化 Message，而是用自定义 POJO 做"中间层"

                    ┌───────────────────────────────────┐
 序列化时            │        ChatMessageWrapper          │
 Message ────────→  │  - messageType: MessageType      │ ────→ JSON
   （值拷贝）       │  - textContent: String           │      （标准属性）
                    │  - metadata: Map<String, Object>  │
                    └───────────────────────────────────┘
                    ┌───────────────────────────────────┐
 反序列化时          │        ChatMessageWrapper          │
 JSON ──────────→   │  - messageType: MessageType      │ ────→ Message
 （反射设值）        │  - textContent: String           │      （按类型 new）
                    │  - metadata: Map<String, Object>  │
                    └───────────────────────────────────┘
```

### 3.2 满足 Hutool 的 JavaBean 约定

```java
@Data                         // Lombok 自动生成 getter/setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageWrapper {
    private MessageType messageType;          // getMessageType() → 属性 messageType ✅
    private String textContent;               // getTextContent() → 属性 textContent  ✅
                                                 // setTextContent() → 可反序列化         ✅
    private Map<String, Object> metadata;     // getMetadata()    → 属性 metadata     ✅
}
```

> ✅ 字段名 `textContent` + getter `getTextContent()` + setter `setTextContent()` 三者一致，Hutool 完整识别。

### 3.3 完整代码

> **文件路径：** `tj-aigc/src/main/java/com/tianji/memory/ChatMessageWrapper.java`

```java
package com.tianji.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.*;

import java.util.Map;

/**
 * 聊天消息包装类
 *
 * 用于解决 Spring AI Message 接口的序列化/反序列化问题
 * Message 是接口，Hutool JSONUtil 无法直接反序列化
 *
 * @Name: ChatMessageWrapper
 * @Author: Natural Pride
 * @CreateTime: 2026/7/5
 * @Description: 消息包装类，保存类型信息用于正确反序列化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageWrapper {

    /**
     * 消息类型：USER, ASSISTANT, SYSTEM
     */
    private MessageType messageType;

    /**
     * 消息文本内容
     */
    private String textContent;

    /**
     * 消息元数据
     */
    private Map<String, Object> metadata;

    /**
     * 将 Spring AI Message 转换为包装类
     *
     * @param message Spring AI 消息对象
     * @return 包装类对象
     */
    public static ChatMessageWrapper fromMessage(Message message) {
        return ChatMessageWrapper.builder()
                .messageType(message.getMessageType())
                // 关键：text 的值通过 getMessage getText() 取出
                .textContent(message.getText())
                .metadata(message.getMetadata())
                .build();
    }

    /**
     * 将包装类转换为 Spring AI Message 实现类
     *
     * @return 对应类型的 Message 实现对象
     * @throws IllegalArgumentException 如果消息类型不支持
     */
    public Message toMessage() {
        return switch (messageType) {
            case USER -> new UserMessage(this.textContent);
            case ASSISTANT -> new AssistantMessage(this.textContent);
            case SYSTEM -> new SystemMessage(this.textContent);
            default -> throw new IllegalArgumentException("不支持的消息类型: " + messageType);
        };
    }
}
```

---

## 四、使用方式

### 4.1 在 ChatMemory 持久化中集成（本项目实际用法）

> **调用路径：** `RedisChatMemory` 是 `ChatMemory` 接口的实现，`ChatServiceImpl` 调用 `ChatMemory.add()` / `.get()` 时自动触发

#### 写入 Redis（序列化）

```java
// RedisChatMemory.add()
messages.forEach(message -> {
    // ① Message → 包装类（值拷贝）
    ChatMessageWrapper wrapper = ChatMessageWrapper.fromMessage(message);
    // ② 包装类 → JSON 字符串（Hutool 可正确序列化）
    String jsonMessage = JSONUtil.toJsonStr(wrapper);
    // ③ 存入 Redis
    listOps.rightPush(jsonMessage);
});
```

#### 读取 Redis（反序列化）

```java
// RedisChatMemory.get()
return jsonMessages.stream()
    .map(json -> {
        // ① JSON → 包装类（反射设值）
        ChatMessageWrapper wrapper = JSONUtil.toBean(json, ChatMessageWrapper.class);
        // ② 包装类 → Message 实现类（按类型 new）
        return wrapper.toMessage();
    })
    .collect(Collectors.toList());
```

### 4.2 通用使用模板

```java
// ========== 任意场景通用模板 ==========

// 1. 序列化：任何 Spring AI Message 子类 → JSON 字符串
Message msg = new UserMessage("用户问题...");
String json = JSONUtil.toJsonStr(ChatMessageWrapper.fromMessage(msg));

// 2. 反序列化：JSON 字符串 → 对应 Message 实现类
Message restored = JSONUtil.toBean(json, ChatMessageWrapper.class).toMessage();
// restored 现在是 UserMessage 对象，getText() 可以正确返回 "用户问题..."

// 3. 批量序列化
List<Message> history = chatMemory.get(conversationId, 20);
List<String> jsons = history.stream()
    .map(JSONUtil::toJsonStr)
    .toList();

// 4. 批量反序列化
List<Message> restored = jsons.stream()
    .map(j -> JSONUtil.toBean(j, ChatMessageWrapper.class).toMessage())
    .toList();
```

---

## 五、数据流转全景图

```
【写入路径】

UserMessage 对象
├── messageType = MessageType.USER
├── getText()   = "你好"      ← 真实值
└── metadata   = {...}
        │
        │ fromMessage()
        ▼
ChatMessageWrapper 对象
├── messageType = MessageType.USER
├── textContent  = "你好"     ← 值拷贝到符合约定的字段
├── metadata    = {...}
│       │ getTextContent() ✅
│       │ setTextContent() ✅
│       │ getMessageType() ✅
│       │ getMetadata()    ✅
        │
        │ JSONUtil.toJsonStr()
        ▼
JSON 字符串
│ {"messageType":"USER","textContent":"你好","metadata":{...}}
│   ↑ 字段名、getter 名、JSON key 三者一致
        │
        │ RPUSH
        ▼
Redis List [CHAT:{conversationId}]


【读取路径】

Redis List
        │
        │ LRANGE
        ▼
JSON 字符串 {"messageType":"USER","textContent":"你好",...}
        │
        │ JSONUtil.toBean(wrapperClass)
        ▼
ChatMessageWrapper
├── messageType = MessageType.USER  ← 保留类型标记
├── textContent  = "你好"
├── metadata    = {...}
        │
        │ toMessage() → switch(messageType)
        ▼
UserMessage 对象                    ← 精确还原到具体实现类
├── getText() = "你好"             ✅ 文本内容恢复
```

---

## 六、复用到其他序列化框架

### 6.1 为什么此方案不仅限于 Hutool

这个方案本质是 **DTO（Data Transfer Object）模式**，与具体序列化框架解耦：

| 序列化框架 | 直接使用 Message | 使用 ChatMessageWrapper |
|------------|------------------|----------------------|
| **Hutool JSONUtil** | ❌ textContent 为 null | ✅ 完整序列化/反序列化 |
| **Jackson（Spring Boot 默认）** | ❌ 反序列化需 type 信息 | ✅ 配合 `@JsonTypeInfo` 可直接用 |
| **FastJSON** | ⚠️ 丢失类型信息，无法还原实现类 | ✅ 按 messageType 还原 |
| **Gson** | ⚠️ 同上 | ✅ 同上 |
| **Protobuf / Thrift** | ❌ 不支持接口 | ✅ 重新定义 message 类型 |

### 6.2 如果可以替换 Hutool 为 Jackson，为什么仍保留 Wrapper？

即使使用 Jackson（Spring Boot 内置）替代 Hutool，保留 ChatMessageWrapper 仍有价值：

1. **框架解耦**：DTO 只表达"需要什么字段"，不暴露 Spring AI 的内部类结构
2. **版本兼容**：Spring AI 升级改字段名时，DTO 可以做兼容映射
3. **可控序列化**：可以选择性忽略敏感字段（如 metadata 中的 userId），避免泄露
4. **向前扩展**：可以添加业务字段（如 `createTime`、`messageId`）而不污染 Spring AI 原始类
5. **统一出口**：同一个 DTO 能同时用于 Redis、DB、MQ、HTTP 多个出口

---

## 七、方案对比一览

```
┌──────────────────────┬──────────────────┬──────────────────┬──────────────────┐
│       维度           │  直接序列化       │  直接序列化       │ ChatMessageWrapper │
│                      │  (Hutool)        │  (Jackson+注解)   │ （自定义 DTO）    │
├──────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ 文本内容             │ ❌ 为 null       │ ⚠️ 序列化OK      │ ✅ 完整保留       │
│ 反序列化还原类       │ ❌ 接口无法实例化│ ⚠️ 需写死实现类   │ ✅ 按类型动态还原 │
│ 跨序列化框架复用     │ ❌ 只适用 Hutool │ ❌ 只适用 Jackson │ ✅ 框架无关       │
│ 业务字段扩展         │ ❌               │ ⚠️ 污染原始类    │ ✅ 独立扩展       │
│ 框架升级兼容         │ ❌               │ ⚠️               │ ✅ 兼容层隔离     │
└──────────────────────┴──────────────────┴──────────────────┴──────────────────┘
```

---

## 八、核心文件索引

| 文件 | 路径 | 职责 |
|------|------|------|
| ChatMessageWrapper | `tj-aigc/src/main/java/com/tianji/memory/ChatMessageWrapper.java` | 包装类：序列化中间层 |
| ChatMemory 接口 | `tj-aigc/src/main/java/com/tianji/memory/ChatMemory.java` | 聊天记忆抽象接口 |
| RedisChatMemory | `tj-aigc/src/main/java/com/tianji/memory/RedisChatMemory.java` | Redis 持久化实现（集成 Wrapper） |
| MessageTypeEnum | `tj-aigc/src/main/java/com/tianji/enums/MessageTypeEnum.java` | 消息类型枚举（业务层） |
| MessageVO | `tj-aigc/src/main/java/com/tianji/vo/MessageVO.java` | 前端消息 VO 展示 |

---

## 九、总结

### 一句话

> Spring AI 的 `AbstractMessage` 字段叫 `textContent`，但 getter 是 `getText()`，违反了 JavaBean 命名约定（Hutool 强依赖的约定），加上 `Message` 本身是接口无法直接实例化，三重因素叠加导致序列化静默丢值。`ChatMessageWrapper` 作为一个 JavaBean 合规的 POJO 中间层，**通过值拷贝 + 类型标记**把这三层不匹配全部抹平。

### 设计启示

1. **接口不是 DTO**：永远不要直接把框架的接口 / 领域对象当作序列化目标
2. **DTO 是防腐层**：跨边界（Redis、MQ、HTTP）传输时，DTO 起隔离作用
3. **值拷贝优于反射 hack**：手动 from/to 方法比用 TypeId、Mixin 等黑盒方案更可维护、更可调试
4. **命名约定很重要**：Hutool、Jackson、FastJSON 都考察 getter/setter，写 POJO 时严格遵守 `getXxx()` ↔ `xxx` 约定
5. **静默失败最可怕**：这种 Bug 不抛异常，只在特定场景暴露（重启服务、跨进程读取），统一 DTO 方案可以从设计上杜绝
