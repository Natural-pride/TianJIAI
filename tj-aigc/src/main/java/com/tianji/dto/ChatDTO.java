package com.tianji.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天数据传输对象 (DTO)
 * 
 * 用于封装前端发送的聊天请求数据，作为 Controller 层与 Service 层之间的数据载体
 * 
 * 设计说明：
 * - 使用 Lombok @Builder 支持链式构造
 * - 两个 @AllArgsConstructor 和 @NothingArgsConstructor 为 Jackson 反序列化提供灵活性
 * 
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3
 * @Description: 聊天请求参数对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatDTO {

    /**
     * 用户输入的问题内容
     * 示例："Java如何入门？"、"帮我推荐一门课程"
     */
    private String question;

    /**
     * 会话 ID，用于标识和关联同一用户的不同聊天会话
     * 由前端创建会话时生成，格式为 UUID（如 "a1b2c3d4e5f6..."）
     * 同一会话的多次对话共享此 ID，实现多轮对话上下文关联
     */
    private String sessionId;

}