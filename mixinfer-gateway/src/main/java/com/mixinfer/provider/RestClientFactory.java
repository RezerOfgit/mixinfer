package com.mixinfer.provider;

import com.mixinfer.router.Endpoint;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches HTTP clients per {@link Endpoint}.
 *
 * <p>Per-endpoint timeouts require per-endpoint client instances, because
 * both RestClient and JDK HttpClient capture connect timeouts at build time.
 * Cache key is the Endpoint value (provider, baseUrl, timeouts, apiKey);
 * endpoints with different timeouts get different clients.
 */
@Component
public class RestClientFactory {

    private final ConcurrentHashMap<Endpoint, RestClient> restClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Endpoint, HttpClient> httpClients = new ConcurrentHashMap<>();

    public RestClient restClient(Endpoint endpoint) {
        return restClients.computeIfAbsent(endpoint, this::buildRestClient);
    }

    public HttpClient httpClient(Endpoint endpoint) {
        return httpClients.computeIfAbsent(endpoint, this::buildHttpClient);
    }

    private RestClient buildRestClient(Endpoint endpoint) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(endpoint.getConnectTimeout())
                        .withReadTimeout(endpoint.getRequestTimeout());
        ClientHttpRequestFactory factory =
                ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder().requestFactory(factory).build();
    }

    private HttpClient buildHttpClient(Endpoint endpoint) {
        return HttpClient.newBuilder()
                .connectTimeout(endpoint.getConnectTimeout())
                .build();
    }
}