package com.mixinfer.router.failure;

import com.mixinfer.exception.ProviderException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFailureClassifierTest {

    private final DefaultFailureClassifier classifier = new DefaultFailureClassifier();

    @Test
    void should_classify_connect_exception_as_connection_failure() {
        assertThat(classifier.classify(new ConnectException("refused")))
                .isEqualTo(FailureType.CONNECTION_FAILURE);
    }

    @Test
    void should_classify_unknown_host_as_connection_failure() {
        assertThat(classifier.classify(new UnknownHostException("dns")))
                .isEqualTo(FailureType.CONNECTION_FAILURE);
    }

    @Test
    void should_classify_http_connect_timeout_as_connect_timeout() {
        assertThat(classifier.classify(new HttpConnectTimeoutException("connect")))
                .isEqualTo(FailureType.CONNECT_TIMEOUT);
    }

    @Test
    void should_classify_http_timeout_as_read_timeout() {
        assertThat(classifier.classify(new HttpTimeoutException("read")))
                .isEqualTo(FailureType.READ_TIMEOUT);
    }

    @Test
    void should_classify_socket_timeout_as_read_timeout() {
        assertThat(classifier.classify(new SocketTimeoutException("read")))
                .isEqualTo(FailureType.READ_TIMEOUT);
    }

    @Test
    void should_classify_ssl_exception_as_connection_failure() {
        assertThat(classifier.classify(new SSLHandshakeException("tls")))
                .isEqualTo(FailureType.CONNECTION_FAILURE);
    }

    @Test
    void should_classify_tls_exception_wrapped_in_provider_exception() {
        ProviderException pe = new ProviderException(
                "wrapped", new SSLHandshakeException("tls"));
        assertThat(classifier.classify(pe))
                .isEqualTo(FailureType.CONNECTION_FAILURE);
    }

    @Test
    void should_classify_connection_exception_wrapped_deeply() {
        // Simulates RestClient wrapping an IOException in multiple layers.
        Throwable deep = new IllegalStateException(
                "outer",
                new RuntimeException(
                        "middle",
                        new ConnectException("refused")));
        ProviderException pe = new ProviderException("wrapped", deep);
        assertThat(classifier.classify(pe))
                .isEqualTo(FailureType.CONNECTION_FAILURE);
    }

    @Test
    void should_classify_http_500_as_5xx() {
        ProviderException pe = new ProviderException("server", 500);
        assertThat(classifier.classify(pe)).isEqualTo(FailureType.HTTP_5XX);
    }

    @Test
    void should_classify_http_401_as_authentication_failure() {
        ProviderException pe = new ProviderException("auth", 401);
        assertThat(classifier.classify(pe))
                .isEqualTo(FailureType.AUTHENTICATION_FAILURE);
    }

    @Test
    void should_classify_http_404_as_model_not_found() {
        ProviderException pe = new ProviderException("missing", 404);
        assertThat(classifier.classify(pe))
                .isEqualTo(FailureType.MODEL_NOT_FOUND);
    }

    @Test
    void should_classify_unknown_error_as_unknown() {
        assertThat(classifier.classify(new RuntimeException("?")))
                .isEqualTo(FailureType.UNKNOWN);
    }
}