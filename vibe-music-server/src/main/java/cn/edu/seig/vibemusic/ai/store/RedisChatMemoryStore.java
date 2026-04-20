package cn.edu.seig.vibemusic.ai.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.AI_CHAT_MEMORY;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.AI_CHAT_MEMORY_TTL_DAYS;

@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 获取会话历史记录
     * @param memoryId 会话ID
     * @return 会话历史记录
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String content = stringRedisTemplate.opsForValue().get(buildKey(memoryId));
        if (!StringUtils.hasText(content)) {
            return Collections.emptyList();
        }
        // 反序列化
        return ChatMessageDeserializer.messagesFromJson(content);
    }

    /**
     * 更新会话历史记录
     * @param memoryId
     * @param messages
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        String content = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key, content, AI_CHAT_MEMORY_TTL_DAYS, TimeUnit.DAYS);
    }

    /**
     * 删除会话历史记录
     * @param memoryId
     */
    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(buildKey(memoryId));
    }

    private String buildKey(Object memoryId) {
        return AI_CHAT_MEMORY + memoryId;
    }
}
