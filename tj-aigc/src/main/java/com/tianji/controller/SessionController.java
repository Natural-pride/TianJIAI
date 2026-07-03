package com.tianji.controller;


import com.tianji.service.ChatSessionService;
import com.tianji.vo.SessionVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Name:SessionController.java
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 14:39
 * @Description:  会话控制器,用于创建和管理会话
 */

@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
@Tag(name = "会话接口", description = "用于创建和管理会话")
public class SessionController {

    private final ChatSessionService chatSessionService;


    /**
     * 新建会话
     * @param num 会话数量
     * @return 会话信息
     */
    @PostMapping
    public SessionVO createSession(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return chatSessionService.createSession(num);
    }

    /**
     * 获取热门会话
     * @return 热门会话信息
     */
    @GetMapping("/hot")
    public List<SessionVO.Example> getHotSessions(@RequestParam(value = "n",defaultValue = "3") Integer num) {
        return chatSessionService.getHotSessions(num);
    }

}