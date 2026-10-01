package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A single message in a conversation.
 * Role is a string (not enum) to stay forward-compatible with new roles.
 */
@Value
@Builder
public class LlmMessage {
    String role;             // "system" | "user" | "assistant" | "tool"
    List<ContentPart> content;
}
