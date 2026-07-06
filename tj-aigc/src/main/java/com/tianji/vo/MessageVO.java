package com.tianji.vo;

import com.tianji.enums.MessageTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @Name:MessageVO.java
 * @Author: Natural Pride
 * @CreateTime: 2026/7/6 20:43
 * @Description: 用于返回聊天记录给前端，包含消息类型（USER/ASSISTANT）和内容
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {

    /**
     * 消息类型，USER表示用户提问，ASSISTANT表示AI的回答
     */
    private MessageTypeEnum type;
    
    /**
     * 消息内容
     */
    private String content;

    /**
     * 附加参数
     */
    private Map<String, Object> params;

}