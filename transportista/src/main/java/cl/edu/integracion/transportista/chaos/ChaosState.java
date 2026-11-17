package cl.edu.integracion.transportista.chaos;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ChaosState {
    private volatile long delayMs = 0;
    private volatile boolean caido = false;

    public void configurar(long delayMs, boolean caido) { this.delayMs = delayMs; this.caido = caido; }
    public long getDelayMs() { return delayMs; }
    public boolean isCaido() { return caido; }

    public void aplicar() {
        if (caido) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transportista caído (chaos)");
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}