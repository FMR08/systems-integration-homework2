package cl.edu.integracion.tracking.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ChaosState {
    private final AtomicBoolean fallarGenerar;

    public ChaosState(@Value("${chaos.tracking.fail:false}") final boolean inicial) {
        this.fallarGenerar = new AtomicBoolean(inicial);
    }

    public boolean isFallarGenerar() {
        return fallarGenerar.get();
    }

    public void setFallarGenerar(final boolean valor) {
        fallarGenerar.set(valor);
    }
}
