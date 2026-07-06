package com.tianji.memory;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.tianji.config.ToolResultHolder;
import com.tianji.constants.Constant;
import org.springframework.ai.chat.messages.*;

import java.util.Map;

/**
 * 消息转换工具类，提供 Message 对象与 Redis 存储格式 JSON 之间的双向转换
 */
public class MessageUtil {

    /**
     * 将 Message 对象转换为 Redis 存储格式的 JSON 字符串
     *
     * 携带 conversationId 是为了在序列化 AssistantMessage 时，通过 conversationId → requestId → params
     * 链路获取工具调用产生的额外参数，最终写入 RedisMessage.params 持久化
     *
     * @param message        需要转换的原始消息对象
     * @param conversationId 会话 ID，作为 ToolResultHolder 中 requestId 的查找 key
     * @return 符合 Redis 存储规范的 JSON 字符串
     */
    public static String toJson(Message message, String conversationId) {
        RedisMessage redisMessage = BeanUtil.toBean(message, RedisMessage.class);
        redisMessage.setTextContent(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            redisMessage.setToolCalls(assistantMessage.getToolCalls());

            // 通过 conversationId → requestId → params 获取工具调用产生的额外参数
            String requestId = Convert.toStr(ToolResultHolder.get(conversationId, Constant.REQUEST_ID));
            if (requestId != null) {
                Map<String, Object> params = ToolResultHolder.get(requestId);
                if (ObjectUtil.isNotEmpty(params)) {
                    redisMessage.setParams(params);
                }
            }
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            redisMessage.setToolResponses(toolResponseMessage.getResponses());
        }
        return JSONUtil.toJsonStr(redisMessage);
    }

    /**
     * 将 Message 对象转换为 Redis 存储格式的 JSON 字符串（不关联 ToolResultHolder 中的 params）
     *
     * @param message 需要转换的原始消息对象
     * @return 符合 Redis 存储规范的 JSON 字符串
     */
    public static String toJson(Message message) {
        RedisMessage redisMessage = BeanUtil.toBean(message, RedisMessage.class);
        redisMessage.setTextContent(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            redisMessage.setToolCalls(assistantMessage.getToolCalls());
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            redisMessage.setToolResponses(toolResponseMessage.getResponses());
        }
        return JSONUtil.toJsonStr(redisMessage);
    }

    /**
     * 将 Redis 存储的 JSON 字符串反序列化为对应的 Message 对象
     *
     * @param json Redis 存储的 JSON 格式消息数据
     * @return 对应类型的 Message 对象
     * @throws RuntimeException 当无法识别的消息类型时抛出异常
     */
    public static Message toMessage(String json) {
        RedisMessage redisMessage = JSONUtil.toBean(json, RedisMessage.class);
        MessageType messageType = MessageType.valueOf(redisMessage.getMessageType());
        switch (messageType) {
            case SYSTEM -> {
                return new SystemMessage(redisMessage.getTextContent());
            }
            case USER -> {
                return new UserMessage(redisMessage.getTextContent(), redisMessage.getMedia(), redisMessage.getMetadata());
            }
            case ASSISTANT -> {
                return new MyAssistantMessage(redisMessage.getTextContent(), redisMessage.getProperties(),
                        redisMessage.getToolCalls(), redisMessage.getParams());
            }
            case TOOL -> {
                return new ToolResponseMessage(redisMessage.getToolResponses(), redisMessage.getMetadata());
            }
        }
        throw new RuntimeException("Message data conversion failed.");
    }

}
