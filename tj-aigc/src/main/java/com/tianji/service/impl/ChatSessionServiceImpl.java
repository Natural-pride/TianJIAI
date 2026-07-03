package com.tianji.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.tianji.common.utils.UserContext;
import com.tianji.config.SessionProperties;
import com.tianji.entity.ChatSession;
import com.tianji.service.ChatSessionService;
import com.tianji.vo.SessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import static com.baomidou.mybatisplus.extension.toolkit.Db.save;

/**
 * @Name: ChatSessionServiceImpl
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 14:40
 * @Description: 会话服务实现类
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final SessionProperties sessionProperties;

    @Override
    public SessionVO createSession(Integer num) {
        // 获取AI助手标题和描述
        SessionVO sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);

        // 随机获取热门话题（防御：配置中 examples 可能为 null 或为空，避免 NPE）
        List<SessionVO.Example> examples = sessionProperties.getExamples();
        if (CollUtil.isEmpty(examples)) {
            sessionVO.setExamples(Collections.emptyList());
        } else {
            sessionVO.setExamples(RandomUtil.randomEleList(examples, num));
        }

        // 随机生成会话ID
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        // 构建持久化对象，并持久化
        ChatSession chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())
                .build();
        // 保存会话信息
        save(chatSession);
        return sessionVO;

    }
}
