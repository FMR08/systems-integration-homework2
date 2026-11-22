package cl.edu.integracion.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class AuthFilter implements GlobalFilter, Ordered {
    @Value("${gateway.auth.token}")
    private String expectedToken;

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String path = exchange.getRequest().getURI().getPath();

        final String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        final String expectedHeader = "Bearer " + expectedToken;

        if (authHeader == null || !authHeader.equals(expectedHeader)) {
            return writeJsonError(
                exchange, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Token de acceso faltante o inválido", path
            );
        }

        return chain.filter(exchange);
    }

    @SuppressWarnings("SameParameterValue")
    private Mono<Void> writeJsonError(
        final ServerWebExchange exchange,
        final HttpStatus status,
        final String code,
        final String message,
        final String path
    ) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        final String body = """
            {"success":false,"error":{"code":"%s","message":"%s","details":[]},"timestamp":"%s","path":"%s"}
            """.formatted(code, message, Instant.now(), path).trim();

        final DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -2; // Se ejecuta primero: autentica antes de contar en el rate limit
    }
}
