package cn.edu.seig.vibemusic.ai.controller;

import cn.edu.seig.vibemusic.ai.model.AiChatRequest;
import cn.edu.seig.vibemusic.ai.service.MusicAssistantService;
import cn.edu.seig.vibemusic.ai.store.RedisChatMemoryStore;
import cn.edu.seig.vibemusic.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final MusicAssistantService musicAssistantService;
    private final RedisChatMemoryStore redisChatMemoryStore;

    /**
     * ai聊天
     * @param request
     * @return
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody @Valid AiChatRequest request) {
        return musicAssistantService.chat(request.getMemoryId(), request.getMessage());
    }

    /**
     * ai聊天（接口调试用）
     * @param request
     * @return
     */
    @PostMapping("/message")
    public Result<String> chatMessage(@RequestBody @Valid AiChatRequest request) {
        String content = musicAssistantService.chat(request.getMemoryId(), request.getMessage())
                .collectList()
                .map(parts -> String.join("", parts))
                .block();
        return Result.success(content);
    }

    /**
     * 清空会话
     * @param memoryId
     * @return
     */
    @DeleteMapping("/memory/{memoryId}")
    public Result<Void> clearMemory(@PathVariable String memoryId) {
        redisChatMemoryStore.deleteMessages(memoryId);
        return Result.success();
    }
}
