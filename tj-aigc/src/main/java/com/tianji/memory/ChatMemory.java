package com.tianji.memory;

import com.alibaba.nacos.shaded.com.google.protobuf.Message;

import java.util.List;

public interface ChatMemory {

    // TODO: consider a non-blocking interface for streaming usages
    // TODO 为什么有一个默认实现？？
    default void add(String conversationId, Message message) {
            this.add(conversationId, List.of(message));
    }

    void add(String conversationId, List<Message> messages);

    List<Message> get(String conversationId, int lastN);

    void clear(String conversationId);

}