package cl.edu.integracion.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    @Value("${gateway.ratelimit.max-requests-per-minute}")
    private int maxRequestsPerMinute;

    private record Window(long minuteEpoch, AtomicInteger count) {}

    private final Map<String, Window> clients = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String clientKey = resolveClientKey(exchange);
        long currentMinute = Instant.now().getEpochSecond() / 60;

        Window window = clients.compute(clientKey, (key, existing) -> {
            if (existing == null || existing.minuteEpoch() != currentMinute) {
                return new Window(currentMinute, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        int remaining = Math.max(0, maxRequestsPerMinute - window.count().get());
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(remaining));

        if (window.count().get() > maxRequestsPerMinute) {
            return writeTooManyRequests(exchange, path);
        }

        return chain.filter(exchange);
    }

    private String resolveClientKey(ServerWebExchange exchange) {
        String clientId = exchange.getRequest().getHeaders().getFirst("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return (remote != null && remote.getAddress() != null)
                ? remote.getAddress().getHostAddress()
                : "unknown-client";
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, String path) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"success":false,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Límite de peticiones por minuto excedido","details":[]},"timestamp":"%s","path":"%s"}
                """.formatted(Instant.now(), path).trim();

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
