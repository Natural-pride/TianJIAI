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
 * 消息转换工具类，提供消息对象与JSON字符串之间的转换功能，主要用于Redis存储格式转换
 */
public class MessageUtil {

    /**
     * 将Message对象转换为Redis存储格式的JSON字符串（重载，携带 conversationId 以关联 ToolResultHolder 中的 params）
     *
     * 存储 AssistantMessage 时，如果是携带工具调用结果的消息（如课程查询、预下单等产生 eventType=1003 额外数据），
     * 需要通过 conversationId 从 ToolResultHolder 中取出 requestId，再通过 requestId 取出 params 列表，
     * 最终写入 RedisMessage.params 字段进行持久化。
     *
     * @param message        需要转换的原始消息对象
     * @param conversationId 会话 ID，作为 ToolResultHolder 中 requestId 的查找 key
     * @return 符合Redis存储规范的JSON字符串
     */
    public static String toJson(Message message, String conversationId) {
        RedisMessage redisMessage = BeanUtil.toBean(message, RedisMessage.class);
        // 设置消息内容
        redisMessage.setTextContent(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            redisMessage.setToolCalls(assistantMessage.getToolCalls());

            // 关键修复：通过 conversationId 获取本次请求的 requestId，
            // 再通过 requestId 获取工具调用产生的额外参数（params），写入 RedisMessage.params 持久化
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
     * 将Message对象转换为Redis存储格式的JSON字符串（保留单参数重载以兼容其他可能的调用方）
     *
     * @param message 需要转换的原始消息对象
     * @return 符合Redis存储规范的JSON字符串
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
     * 将Redis存储的JSON字符串反序列化为对应的Message对象
     *
     * @param json Redis存储的JSON格式消息数据
     * @return 对应类型的Message对象
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