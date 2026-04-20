package cn.edu.seig.vibemusic.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        tools = "musicAssistantTool"
)
public interface MusicAssistantService {

    @SystemMessage(fromResource = "prompts/music-assistant-system.txt")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);
}
