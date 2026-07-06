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
        // 从配置文件复制属性到SessionVO
        SessionVO sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);

        // 处理热门话题（examples），随机获取num个，避免每次返回一样的顺序
        List<SessionVO.Example> examples = sessionProperties.getExamples();
        if (CollUtil.isEmpty(examples)) {
            // 防御性编程：如果配置中没有热门话题，返回空列表，避免NPE
            sessionVO.setExamples(Collections.emptyList());
        } else {
            sessionVO.setExamples(RandomUtil.randomEleList(examples, num));
        }

        // 生成唯一的会话ID，保证全局唯一，避免会话冲突
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        // 构建持久化对象，只保存sessionId和userId，其他字段在数据库层有默认值
        ChatSession chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())
                .build();

        // 保存到数据库
        save(chatSession);

        return sessionVO;
    }

    /**
     * 获取热门会话
     * @return 热门会话列表
     */
    @Override
    public List<SessionVO.Example> getHotSessions(Integer num) {
        return RandomUtil.randomEleList(sessionProperties.getExamples(), num);
    }

    private final ChatMemory chatMemory;
    // 历史消息数量，默认1000条
    public static final int HISTORY_MESSAGE_COUNT = 1000;

    @Override
    public List<MessageVO> queryBySessionId(String sessionId) {
        // 根据会话ID获取对话ID，从Redis中获取历史消息
        String conversationId = ChatService.getConversationId(sessionId);
        List<Message> messageList = chatMemory.get(conversationId, HISTORY_MESSAGE_COUNT);
        // 过滤并转换消息列表
        return StreamUtil.of(messageList)
                // 只保留用户消息和助手消息，过滤掉系统消息等其他类型
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                .map(message -> MessageVO.builder()
                        .content(message.getText())
                        .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                        .build())
                .toList();
    }
}
