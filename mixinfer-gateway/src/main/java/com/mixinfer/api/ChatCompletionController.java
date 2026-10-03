package com.mixinfer.api;

import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.service.ChatCompletionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI-compatible entry point.
 * All authentication is handled upstream by {@link com.mixinfer.auth.ApiKeyFilter}.
 */
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService service;

    public ChatCompletionController(ChatCompletionService service) {
        this.service = service;
    }

    @PostMapping("/chat/completions")
    public OpenAIChatResponse chatCompletions(@RequestBody OpenAIChatRequest request) {
        return service.handle(request);
    }
}