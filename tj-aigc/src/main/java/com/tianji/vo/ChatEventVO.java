package com.tianji.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天事件值对象 (VO)
 * 
 * 用于封装流式聊天响应中的每个事件，通过 SSE 协议推送给前端
 * 
 * 事件类型说明：
 * - 1001 (DATA)：普通数据事件，携带 AI 回复的文本片段，前端逐段渲染
 * - 1002 (STOP)：停止事件，流结束标志，前端收到后关闭 SSE 连接
 * - 1003 (PARAM)：参数事件（预留），未来可用于动态调整 AI 行为参数
 * 
 * SSE 响应格式示例：
 *   data: {"eventData":"您好","eventType":1001}
 *   data: {"eventType":1002}
 * 
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3
 * @Description: 聊天流式响应事件对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatEventVO {

    /**
     * 事件携带的业务数据
     * - DATA 事件时：为 String 类型的 AI 回复文本片段
     * - STOP 事件时：为 null
     */
    private Object eventData;

    /**
     * 事件类型标识，参考 ChatEventTypeEnum 枚举
     * 1001 - 数据事件
     * 1002 - 停止事件
     * 1003 - 参数事件（预留）
     */
    private int eventType;

}