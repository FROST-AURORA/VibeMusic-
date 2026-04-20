package cn.edu.seig.vibemusic.ai.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiChatRequest {

    @NotBlank
    private String memoryId;

    @NotBlank
    private String message;
}
