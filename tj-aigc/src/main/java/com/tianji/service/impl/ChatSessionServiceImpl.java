package com.tianji.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.UserContext;
import com.tianji.config.SessionProperties;
import com.tianji.entity.ChatSession;
import com.tianji.enums.MessageTypeEnum;
import com.tianji.mapper.ChatSessionMapper;
import com.tianji.service.ChatService;
import com.tianji.service.ChatSessionService;
import com.tianji.vo.MessageVO;
import com.tianji.vo.SessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {


    private final SessionProperties sessionProperties;

    @Override
    public SessionVO createSession(Integer num) {
        // 获取AI助手标题和描述
        SessionVO sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);

        // 随机获取热门话题（防御：配置中 examples 可能为 null 或为空，避免 NPE）
        List<SessionVO.Example> examples = sessionProperties.getExamples();
        if (CollUtil.isEmpty(examples)) {
            // 防御：配置中 examples 可能为 null 或为空，避免 NPE
            sessionVO.setExamples(Collections.emptyList());
        } else {
            // 随机获取热门话题，数量为 num
            sessionVO.setExamples(RandomUtil.randomEleList(examples, num));
        }

        // 随机生成会话ID
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        // 构建持久化对象(组装会话信息)，并持久化
        ChatSession chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())
                .build();
        // 保存会话信息
        save(chatSession);
        return sessionVO;

    }

    /**
     * 获取热门会话
     * @return 热门会话列表
     */
    @Override
    public List<SessionVO.Example> getHotSessions(Integer num) {
        return RandomUtil.randomEleList(sessionProperties.getExamples(),num);
    }

    private final ChatMemory chatMemory;
    // 历史消息数量，默认1000条
    public static final int HISTORY_MESSAGE_COUNT = 1000;

    @Override
    public List<MessageVO> queryBySessionId(String sessionId) {
        // 根据会话ID获取对话ID
        String conversationId = ChatService.getConversationId(sessionId);
        // 从Redis中获取历史消息
        List<Message> messageList = chatMemory.get(conversationId, HISTORY_MESSAGE_COUNT);
        // 过滤并转换消息列表
        return StreamUtil.of(messageList)
                // 过滤掉非用户消息和助手消息
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                // 转换为MessageVO对象
                .map(message -> MessageVO.builder()
                        .content(message.getText())
                        .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                        .build())
                .toList();
    }
}
