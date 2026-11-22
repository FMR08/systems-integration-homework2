package cl.edu.integracion.bff.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clientes de lectura del BFF. Todas las llamadas tienen timeout corto.
 * Las dependencias secundarias (inventario, transportista, tracking) pasan por
 * Circuit Breaker + Bulkhead y degradan a un bloque "no disponible" en vez de fallar.
 */
@Component
public class DownstreamClient {
    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    private final RestTemplate http;
    private final String orquestadorUrl;
    private final String inventarioUrl;
    private final String transportistaUrl;
    private final String trackingUrl;

    public DownstreamClient(
        final RestTemplateBuilder builder,
        @Value("${servicios.orquestador.url}") final String orquestadorUrl,
        @Value("${servicios.inventario.url}") final String inventarioUrl,
        @Value("${servicios.transportista.url}") final String transportistaUrl,
        @Value("${servicios.tracking.url}") final String trackingUrl
    ) {
        this.http = builder
            .connectTimeout(Duration.ofMillis(500))
            .readTimeout(Duration.ofMillis(1500))
            .build();
        this.orquestadorUrl = orquestadorUrl;
        this.inventarioUrl = inventarioUrl;
        this.transportistaUrl = transportistaUrl;
        this.trackingUrl = trackingUrl;
    }

    /**
     * Fuente principal: si falla, el BFF propaga el error.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> despacho(final String transaccionId) {
        return http.getForObject(orquestadorUrl + "/api/despachos/{id}", Map.class, transaccionId);
    }

    @CircuitBreaker(name = "inventario", fallbackMethod = "degradado")
    @Bulkhead(name = "inventario", type = Bulkhead.Type.SEMAPHORE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> reserva(final String transaccionId) {
        return http.getForObject(inventarioUrl + "/api/inventory/reservations/{id}", Map.class, transaccionId);
    }

    @CircuitBreaker(name = "transportista", fallbackMethod = "degradado")
    @Bulkhead(name = "transportista", type = Bulkhead.Type.SEMAPHORE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> retiro(final String transaccionId) {
        return http.getForObject(transportistaUrl + "/api/shipping/pickups/{id}", Map.class, transaccionId);
    }

    @CircuitBreaker(name = "tracking", fallbackMethod = "degradado")
    @Bulkhead(name = "tracking", type = Bulkhead.Type.SEMAPHORE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> tracking(final String transaccionId) {
        return http.getForObject(trackingUrl + "/api/tracking/by-transaction/{id}", Map.class, transaccionId);
    }

    @SuppressWarnings("unused")
    private Map<String, Object> degradado(final String transaccionId, final Throwable t) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (t instanceof final HttpClientErrorException ex && ex.getStatusCode() == HttpStatus.NOT_FOUND) {
            out.put("disponible", true);
            out.put("encontrado", false);
            return out;
        }
        log.warn("[BFF] degradación para {}: {}", transaccionId, t.getClass().getSimpleName());
        out.put("disponible", false);
        out.put("motivo", t.getClass().getSimpleName());
        return out;
    }
}
