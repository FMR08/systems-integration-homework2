package cl.edu.integracion.transportista.chaos;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/shipping/chaos")
public class ChaosController {
    private final ChaosState chaos;
    public ChaosController(ChaosState chaos) { this.chaos = chaos; }

    @PostMapping
    public Map<String, Object> configurar(@RequestParam(defaultValue = "0") long delayMs,
                                          @RequestParam(defaultValue = "false") boolean down) {
        chaos.configurar(delayMs, down);
        return Map.of("delayMs", delayMs, "down", down);
    }

    @GetMapping
    public Map<String, Object> estado() {
        return Map.of("delayMs", chaos.getDelayMs(), "down", chaos.isCaido());
    }
}