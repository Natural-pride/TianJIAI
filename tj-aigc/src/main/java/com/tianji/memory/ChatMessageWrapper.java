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
     * 消息类型：USER, ASSISTANT, SYSTEM, TOOL
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
