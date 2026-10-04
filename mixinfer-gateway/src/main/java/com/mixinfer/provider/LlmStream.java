package com.mixinfer.provider;

import com.mixinfer.domain.LlmStreamChunk;

import java.io.IOException;

/**
 * A cancellable stream of LLM chunks from an upstream provider.
 *
 * <p>Unlike {@link java.util.stream.Stream}, this abstraction models a
 * long-lived, cancellable IO stream with explicit resource release.
 * Callers must close it, typically via try-with-resources.
 */
public interface LlmStream extends AutoCloseable {

    /**
     * Returns the next chunk, or null if the stream is exhausted.
     * Blocks until a chunk is available.
     *
     * @throws IOException if the underlying stream fails
     */
    LlmStreamChunk next() throws IOException;

    /**
     * Cancels the stream and releases upstream resources (e.g. the HTTP
     * connection). Idempotent. After close(), {@link #next()} returns null.
     */
    @Override
    void close();
}