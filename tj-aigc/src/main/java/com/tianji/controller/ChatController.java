package com.tianji.controller;


import com.tianji.common.annotations.NoWrapper;
import com.tianji.dto.ChatDTO;
import com.tianji.service.ChatService;
import com.tianji.vo.ChatEventVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 聊天控制器
 * 负责处理AI聊天的HTTP请求，包括流式聊天和停止聊天功能
 * 
 * 核心特性：
 * 1. 返回 Flux<ChatEventVO> 实现 SSE 流式响应
 * 2. 使用 @NoWrapper 注解绕过统一响应包装，因为 SSE 协议要求纯文本格式
 * 3. 采用 EventSource 协议，前端通过 EventSource API 逐段接收 AI 回复
 * 
 * @Name: ChatController
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:13
 * @Description: 聊天控制器，处理流式聊天和停止请求
 */

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/chat")
@Tag(name = "会话接口", description = "聊天相关接口")
public class ChatController {

    private final ChatService chatService;

    /**
     * 流式聊天接口
     * 
     * 请求格式：POST /chat，Content-Type: application/json
     * 请求体示例：{"question": "你好", "sessionId": "abc123"}
     * 
     * 响应格式：SSE (Server-Sent Events)
     * 响应示例：
     *   data: {"eventData":"您好","eventType":1001}
     *   data: {"eventType":1002}
     * 
     * @param chatDTO 聊天数据传输对象，包含用户问题和会话ID
     * @return SSE 事件流，包含 AI 回复片段和结束标志
     */
    @NoWrapper
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO){
        return chatService.chat(chatDTO.getQuestion(),chatDTO.getSessionId());
    }

    /**
     * 停止聊天接口
     * 
     * 请求格式：POST /chat/stop?sessionId=abc123
     * 
     * 应用场景：用户在 AI 回复过程中点击"停止生成"按钮
     * 实现原理：清除生成状态标记，触发 takeWhile 停止流式输出
     * 
     * @param sessionId 会话 ID，用于标识需要停止的对话
     */
    @PostMapping("/stop")
    public void stop(@RequestParam("sessionId") String sessionId) {
        chatService.stop(sessionId);
    }

}
