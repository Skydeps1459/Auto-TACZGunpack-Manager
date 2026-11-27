package com.skydeps.autotacz.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpFetcher} 的行为测试。
 *
 * <p>核心用例是 {@link #usesRealProxySelectorButStillReachesLoopback()}：
 * 它用<b>默认构造函数</b>（即自动探测代理）访问本地服务。因为 Java 的
 * HttpClient 读系统代理/环境变量后会把回环流量也一并吞掉，如果没有
 * {@link ProxySupport} 的绕过规则，这个用例就会超时——而真实后果是
 * 玩家的本地整合包、局域网镜像全部失效。
 */
class HttpFetcherTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/manifest.json", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"packs\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing.json", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("默认构造函数在系统配置了代理时，仍能直连本地回环")
    void usesRealProxySelectorButStillReachesLoopback() throws IOException {
        // 缺省构造 = 自动探测代理。本机若有 HTTP_PROXY 环境变量（测试机通常有），
        // 这里就是真实的「代理已配置」场景。
        HttpFetcher fetcher = new HttpFetcher();

        Optional<String> body = fetcher.fetchText(URI.create(baseUrl + "/manifest.json"));

        assertTrue(body.isPresent(), "本地回环请求被代理拦走了，回环绕过规则失效");
        assertEquals("{\"packs\":[]}", body.get());
        assertEquals(1, hits.get());
    }

    @Test
    @DisplayName("显式传 null 代理选择器时行为退化为直连")
    void nullSelectorMeansDirect() throws IOException {
        HttpFetcher fetcher = new HttpFetcher(java.time.Duration.ofSeconds(5), (ProxySelector) null);
        assertTrue(fetcher.fetchText(URI.create(baseUrl + "/manifest.json")).isPresent());
    }

    @Test
    @DisplayName("非 2xx 返回 empty，而不是抛异常")
    void nonSuccessReturnsEmpty() throws IOException {
        HttpFetcher fetcher = new HttpFetcher(java.time.Duration.ofSeconds(5), (ProxySelector) null);
        assertFalse(fetcher.fetchText(URI.create(baseUrl + "/missing.json")).isPresent());
    }
}