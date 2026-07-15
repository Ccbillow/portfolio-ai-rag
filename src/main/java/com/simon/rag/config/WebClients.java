package com.simon.rag.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * Shared WebClient plumbing — one place for the Netty connect/read-timeout
 * boilerplate previously duplicated by every outbound HTTP client (Qdrant, Cohere).
 */
public final class WebClients {

    private WebClients() {}

    public static ReactorClientHttpConnector connector(int connectTimeoutMs, int readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS)));
        return new ReactorClientHttpConnector(httpClient);
    }
}
