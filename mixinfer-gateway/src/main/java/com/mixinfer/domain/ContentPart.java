package com.mixinfer.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A single piece of content within a message.
 * V0.1 only supports text; future versions will add image, audio, etc.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentPart.TextPart.class, name = "text")
})
public sealed interface ContentPart {

    record TextPart(String text) implements ContentPart {}
}
