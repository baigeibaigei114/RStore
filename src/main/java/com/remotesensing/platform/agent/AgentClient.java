package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** Authenticated internal HTTP only; never forwards browser credentials or retries requests. */
@Component
public class AgentClient {
    private final AgentProperties properties;
    private final HttpClient http;
    private final Semaphore slots;
    public AgentClient(AgentProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getConnectSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.slots = new Semaphore(properties.getConcurrentCalls());
    }
    public JsonNode post(String operation, JsonNode body, Duration remaining) {
        if (!properties.isEnabled() || properties.getInternalToken().length() < 32)
            throw new AgentFailure("MODEL_UNAVAILABLE", "Agent服务未启用或未配置", 503, true);
        if (!Set.of("decisions", "answers", "explanations").contains(operation)) throw AgentJson.input();
        byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
        if (input.length > 65536) throw new AgentFailure("MODEL_INPUT_TOO_LARGE", "内部请求超过预算", 400, false);
        if (remaining.isNegative() || remaining.isZero())
            throw new AgentFailure("MODEL_TIMEOUT", "模型回合超过时限", 503, true);
        if (!slots.tryAcquire()) throw new AgentFailure("MODEL_BUSY", "Agent请求并发已满", 429, true);
        try {
            String base = properties.getBaseUrl().replaceAll("/+$", "");
            URI uri = URI.create(base + "/internal/v1/" + operation);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw AgentJson.input();
            Duration timeout = remaining.compareTo(Duration.ofSeconds(properties.getRequestSeconds())) < 0
                    ? remaining : Duration.ofSeconds(properties.getRequestSeconds());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json").header("X-Internal-Token", properties.getInternalToken())
                    .header("X-Request-Id", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(input)).build();
            var pending = http.sendAsync(request, info -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS); }
            catch (java.util.concurrent.TimeoutException e) {
                pending.cancel(true);
                throw new AgentFailure("MODEL_TIMEOUT", "Agent响应超过请求时限", 503, true);
            } catch (InterruptedException e) { pending.cancel(true); throw e; }
            byte[] bytes = response.body();
            JsonNode output;
            try { output = AgentJson.parse(new String(bytes, StandardCharsets.UTF_8)); }
            catch (AgentFailure e) { throw new AgentFailure("MODEL_OUTPUT_INVALID", "内部响应格式不合法", 503, true); }
            if (response.statusCode() != 200) {
                String code = output.path("errorCode").asText();
                if (!code.matches("[A-Z][A-Z_]{0,79}")) code = "MODEL_UNAVAILABLE";
                AgentFailure failure = new AgentFailure(code, "Agent生成或检索失败，请查看安全步骤后重试", 503,
                        code.equals("MODEL_OUTPUT_INVALID") || output.path("retryable").asBoolean(false));
                failure.trace = AgentProtocol.trace(output.path("trace"));
                throw failure;
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentFailure("MODEL_UNAVAILABLE", "Agent请求中断", 503, true);
        } catch (java.util.concurrent.ExecutionException | IllegalArgumentException e) {
            throw new AgentFailure("MODEL_UNAVAILABLE", "Agent请求失败或超时", 503, true);
        } finally { slots.release(); }
    }

    /** The request deadline covers the entire body, with cancellation before unbounded buffering. */
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 65536) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Bounded response exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
