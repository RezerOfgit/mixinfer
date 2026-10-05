package com.mixinfer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.service.ChatCompletionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * OpenAI-compatible entry point.
 *
 * <p>Authentication is handled upstream by
 * {@link com.mixinfer.auth.ApiKeyFilter}. This controller writes the
 * response manually so that streaming and non-streaming paths can share
 * the same endpoint.
 */
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService service;
    private final ObjectMapper objectMapper;

    public ChatCompletionController(ChatCompletionService service,
                                    ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody OpenAIChatRequest request,
                                HttpServletResponse response) throws IOException {
        if (Boolean.TRUE.equals(request.getStream())) {
            service.handleStream(request, response);
        } else {
            OpenAIChatResponse result = service.handle(request);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), result);
        }
    }
}