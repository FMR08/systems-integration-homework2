package cl.edu.integracion.orquestador.client;

import cl.edu.integracion.orquestador.dto.TransportePasoRequest;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
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

    public TransportistaClient(RestTemplateBuilder builder,
                               @Value("${servicios.transportista.url}") String baseUrl) {
        this.baseUrl = baseUrl;
        // Timeout corto: el transportista es la dependencia frágil
        this.agendarTemplate = builder
                .connectTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ofSeconds(2))
                .build();
        // La compensación tolera más espera
        this.cancelarTemplate = builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(4))
                .build();
    }

    @CircuitBreaker(name = "transportista", fallbackMethod = "agendarFallback")
    @Bulkhead(name = "transportista", type = Bulkhead.Type.SEMAPHORE)
    public void agendar(TransportePasoRequest req) {
        agendarTemplate.postForEntity(baseUrl + "/api/shipping/schedule", req, String.class);
    }

    @SuppressWarnings("unused")
    private void agendarFallback(TransportePasoRequest req, Throwable t) {
        if (t instanceof HttpClientErrorException http) {
            throw http; // error de negocio (409, etc.): no es indisponibilidad
        }
        log.warn("[TRANSPORTISTA] fallback activado: {}", t.getClass().getSimpleName());
        throw new TransportistaNoDisponibleException(
                "Transportista no disponible (" + t.getClass().getSimpleName() + ")");
    }

    /** Compensación: fuera del breaker, con retry y backoff. */
    public void cancelar(String transaccionId) {
        int intentos = 3;
        RuntimeException ultimo = null;
        for (int i = 0; i < intentos; i++) {
            try {
                cancelarTemplate.postForEntity(baseUrl + "/api/shipping/cancel",
                        new TransportePasoRequest(transaccionId, null), String.class);
                return;
            } catch (RuntimeException e) {
                ultimo = e;
                log.warn("[TRANSPORTISTA] cancel intento {}/{} falló: {}", i + 1, intentos, e.getMessage());
                try {
                    Thread.sleep(500L * (1L << i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw ultimo;
    }
}