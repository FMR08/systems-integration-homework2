package cl.edu.integracion.orquestador.client;

import cl.edu.integracion.orquestador.dto.TransportePasoRequest;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class TransportistaClient {
    private static final Logger log = LoggerFactory.getLogger(TransportistaClient.class);

    private final RestTemplate agendarTemplate;
    private final RestTemplate cancelarTemplate;
    private final String baseUrl;

    public TransportistaClient(
        final RestTemplateBuilder builder,
        @Value("${servicios.transportista.url}") final String baseUrl,
        @Value("${servicios.transportista.agendar.connect-timeout-ms:1000}") final long agendarConnectMs,
        @Value("${servicios.transportista.agendar.read-timeout-ms:2000}") final long agendarReadMs,
        @Value("${servicios.transportista.cancelar.connect-timeout-ms:2000}") final long cancelarConnectMs,
        @Value("${servicios.transportista.cancelar.read-timeout-ms:4000}") final long cancelarReadMs
    ) {
        this.baseUrl = baseUrl;
        this.agendarTemplate = builder
            .connectTimeout(Duration.ofMillis(agendarConnectMs))
            .readTimeout(Duration.ofMillis(agendarReadMs))
            .build();
        this.cancelarTemplate = builder
            .connectTimeout(Duration.ofMillis(cancelarConnectMs))
            .readTimeout(Duration.ofMillis(cancelarReadMs))
            .build();
        log.info("[TRANSPORTISTA] timeouts agendar={}ms/{}ms cancelar={}ms/{}ms",
            agendarConnectMs, agendarReadMs, cancelarConnectMs, cancelarReadMs);
    }

    @CircuitBreaker(name = "transportista", fallbackMethod = "agendarFallback")
    @Bulkhead(name = "transportista", type = Bulkhead.Type.SEMAPHORE)
    public void agendar(final TransportePasoRequest req) {
        agendarTemplate.postForEntity(baseUrl + "/api/shipping/schedule", req, String.class);
    }

    @SuppressWarnings("unused")
    private void agendarFallback(final TransportePasoRequest req, final Throwable t) {
        if (t instanceof final HttpClientErrorException http) {
            throw http;
        }
        log.warn("[TRANSPORTISTA] fallback activado: {}", t.getClass().getSimpleName());
        final boolean noEnviada = t instanceof CallNotPermittedException || t instanceof BulkheadFullException;
        throw new TransportistaNoDisponibleException(
            "Transportista no disponible (" + t.getClass().getSimpleName() + ")",
            noEnviada
        );
    }

    /**
     * Compensación: fuera del breaker, con retry y backoff.
     */
    public void cancelar(final String transaccionId) {
        final int intentos = 3;
        RuntimeException ultimo = null;
        for (int i = 0; i < intentos; i++) {
            try {
                cancelarTemplate.postForEntity(
                    baseUrl + "/api/shipping/cancel",
                    new TransportePasoRequest(transaccionId, null),
                    String.class
                );
                return;
            } catch (final RuntimeException e) {
                ultimo = e;
                log.warn("[TRANSPORTISTA] cancel intento {}/{} falló: {}", i + 1, intentos, e.getMessage());
                try {
                    Thread.sleep(500L * (1L << i));
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw ultimo;
    }
}
