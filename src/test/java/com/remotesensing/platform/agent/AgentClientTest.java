package com.remotesensing.platform.agent;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class AgentClientTest {
    HttpServer server;
    AgentClient client;
    AtomicInteger requests;
    String body;
    int status;
    @BeforeEach void setup() throws Exception {
        requests = new AtomicInteger(); body = "{\"ok\":true}"; status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/decisions", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("synthetic-internal-token-over-32-characters");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Request-Id")).matches("[a-f0-9-]{36}");
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); AgentProperties properties = new AgentProperties(); properties.setEnabled(true);
        properties.setInternalToken("synthetic-internal-token-over-32-characters");
        properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()); client = new AgentClient(properties);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void authenticatedInternalRequestReturnsJsonOnce() {
        assertThat(client.post("decisions", AgentJson.object(),Duration.ofSeconds(2)).path("ok").asBoolean()).isTrue();
        assertThat(requests).hasValue(1);
    }
    @Test void malformedOrOversizedResponseAndInputFailClosed() {
        body="{\"x\":1,\"x\":2}";
        assertThatThrownBy(() -> client.post("decisions",AgentJson.object(),Duration.ofSeconds(2))).isInstanceOf(AgentFailure.class);
        body="x".repeat(65537);
        assertThatThrownBy(() -> client.post("decisions",AgentJson.object(),Duration.ofSeconds(2))).isInstanceOf(AgentFailure.class);
        assertThat(requests).hasValue(2);
        assertThatThrownBy(() -> client.post("decisions",AgentJson.object().put("x","x".repeat(65537)),Duration.ofSeconds(2))).isInstanceOf(AgentFailure.class);
        assertThat(requests).hasValue(2);
    }
    @Test void knownFailureUsageSurvivesWithoutRawProviderMessage() {
        status=503; body="{\"errorCode\":\"MODEL_OUTPUT_INVALID\",\"message\":\"password=synthetic-only\",\"retryable\":false,\"trace\":{\"usage\":{\"total_tokens\":77},\"reasoning_content\":\"private\"}}";
        try { client.post("decisions",AgentJson.object(),Duration.ofSeconds(2)); fail("Expected failure"); }
        catch (AgentFailure error) {
            assertThat(error.errorCode).isEqualTo("MODEL_OUTPUT_INVALID"); assertThat(error.trace.path("usage").path("total_tokens").intValue()).isEqualTo(77);
            assertThat(error.retryable).isTrue(); // Explicit user retry consumes the same persistent budget; no automatic retry.
            assertThat(error.getMessage()+error.trace).doesNotContain("synthetic-only","private");
        }
        assertThat(requests).hasValue(1);
    }
    @Test void requestDeadlineCoversStalledBodyAndDoesNotRetry() {
        server.removeContext("/internal/v1/decisions");
        server.createContext("/internal/v1/decisions", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200,100); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.post("decisions",AgentJson.object(),Duration.ofMillis(200))).isInstanceOf(AgentFailure.class);
        assertThat(Duration.ofNanos(System.nanoTime()-started).toMillis()).isLessThan(1200);
        assertThat(requests).hasValue(1);
    }
    @Test void internalRedirectDoesNotForwardTokenToAnotherPath() {
        server.removeContext("/internal/v1/decisions");
        server.createContext("/internal/v1/decisions", exchange -> { requests.incrementAndGet(); exchange.getResponseHeaders().add("Location","/redirect"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.createContext("/redirect", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        assertThatThrownBy(() -> client.post("decisions",AgentJson.object(),Duration.ofSeconds(2))).isInstanceOf(AgentFailure.class);
        assertThat(requests).hasValue(1);
    }
}
