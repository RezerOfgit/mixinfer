package com.mixinfer.exception;

import java.io.IOException;

/**
 * Signals that the downstream client closed the connection while MixInfer
 * was writing to it.
 *
 * <p>Distinguished from generic upstream IO failures so that settlement
 * and health accounting can treat the two cases differently:
 * <ul>
 *   <li>Client disconnect settles as {@code client_disconnected} and does
 *       not count against endpoint health.</li>
 *   <li>Upstream mid-stream failure settles as {@code upstream_stream_error}
 *       and does count against endpoint health.</li>
 * </ul>
 */
public class ClientDisconnectedException extends IOException {

    public ClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}