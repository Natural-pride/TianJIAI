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
 * @Name: ChatController
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:13
 * @Description: 聊天控制器
 */

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/chat")
@Tag(name = "会话接口", description = "聊天相关接口")
public class ChatController {

    private final ChatService chatService;

    /**
     * 流式聊天
     * @param chatDTO 聊天DTO
     * @return 聊天响应流
     */
    @NoWrapper
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO){
        return chatService.chat(chatDTO.getQuestion(),chatDTO.getSessionId());
    }

}
