package cl.edu.integracion.tracking.chaos;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tracking/chaos")
public class ChaosController {
    private final ChaosState chaos;

    public ChaosController(final ChaosState chaos) {
        this.chaos = chaos;
    }

    @PostMapping
    public Map<String, Object> configurar(@RequestParam final boolean fail) {
        chaos.setFallarGenerar(fail);
        return Map.of("fallarGenerar", fail);
    }

    @GetMapping
    public Map<String, Object> estado() {
        return Map.of("fallarGenerar", chaos.isFallarGenerar());
    }
}
