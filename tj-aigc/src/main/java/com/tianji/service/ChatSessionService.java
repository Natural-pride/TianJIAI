package com.tianji.service;


import com.tianji.vo.SessionVO;

import java.util.List;

/**
 * @Name: ChatSessionService
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 14:39
 * @Description: 会话服务接口
 */

public interface ChatSessionService {

    // 创建会话
    SessionVO createSession(Integer num);

    // 获取热门会话
    List<SessionVO.Example> getHotSessions(Integer num);
}
