package com.mixinfer.router.failure;

import com.mixinfer.exception.ProviderException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Default classifier mapping transport-level and HTTP-level failures into
 * {@link FailureType}.
 *
 * <p>Classification is intentionally coarse in V0.3: only the categories
 * that drive failover decisions are distinguished.
 */
@Component
public class DefaultFailureClassifier implements FailureClassifier {

    @Override
    public FailureType classify(Throwable error) {
        if (error instanceof ProviderException pe) {
            FailureType byStatus = classifyHttpStatus(pe.getHttpStatus());
            if (byStatus != null) {
                return byStatus;
            }
            return classifyCause(pe.getCause());
        }
        return classifyCause(error);
    }

    private FailureType classifyHttpStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 408 -> FailureType.HTTP_408;
            case 429 -> FailureType.HTTP_429;
            case 401, 403 -> FailureType.AUTHENTICATION_FAILURE;
            case 400 -> FailureType.INVALID_REQUEST;
            case 404 -> FailureType.MODEL_NOT_FOUND;
            default -> status >= 500 ? FailureType.HTTP_5XX : FailureType.UNKNOWN;
        };
    }

    private FailureType classifyCause(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            FailureType type = classifyOne(current);
            if (type != FailureType.UNKNOWN) {
                return type;
            }
            current = current.getCause();
        }
        return FailureType.UNKNOWN;
    }

    private FailureType classifyOne(Throwable t) {
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
            return FailureType.CONNECTION_FAILURE;
        }
        if (t instanceof HttpConnectTimeoutException) {
            return FailureType.CONNECT_TIMEOUT;
        }
        if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
            return FailureType.READ_TIMEOUT;
        }
        return FailureType.UNKNOWN;
    }
}