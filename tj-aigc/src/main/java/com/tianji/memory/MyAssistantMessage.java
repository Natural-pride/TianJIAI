package com.tianji.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.Media;

import java.util.List;
import java.util.Map;

/**
 * 自定义 AssistantMessage，扩展 params 字段用于存储工具调用产生的额外参数
 *
 * @Name: MyAssistantMessage
 * @Author: Natural Pride
 * @CreateTime: 2026/7/6 15:30
 * @Description: 自定义 AssistantMessage 类，用于存储额外的参数信息
 */
public class MyAssistantMessage extends AssistantMessage {

    private Map<String, Object> params = Map.of();

    public MyAssistantMessage(String content) {
        super(content);
    }

    public MyAssistantMessage(String content, Map<String, Object> properties) {
        super(content, properties);
    }

    public MyAssistantMessage(String content, Map<String, Object> properties, List<ToolCall> toolCalls) {
        super(content, properties, toolCalls);
    }

    public MyAssistantMessage(String content, Map<String, Object> properties, List<ToolCall> toolCalls, Map<String, Object> params) {
        super(content, properties, toolCalls);
        this.params = params;
    }

    public MyAssistantMessage(String content, Map<String, Object> properties, List<ToolCall> toolCalls, List<Media> media) {
        super(content, properties, toolCalls, media);
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
