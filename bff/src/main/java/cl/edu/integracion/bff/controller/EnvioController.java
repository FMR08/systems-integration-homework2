package cl.edu.integracion.bff.controller;

import cl.edu.integracion.bff.client.DownstreamClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** BFF web: una sola respuesta a medida con datos de 4 servicios. */
@RestController
@RequestMapping("/api/bff")
public class EnvioController {

    private final DownstreamClient clients;

    public EnvioController(DownstreamClient clients) {
        this.clients = clients;
    }

    @GetMapping("/envios/{transaccionId}")
    public ResponseEntity<Map<String, Object>> vistaEnvio(@PathVariable String transaccionId) {
        Map<String, Object> despacho;
        try {
            despacho = clients.despacho(transaccionId);
        } catch (HttpClientErrorException.NotFound e) {
            return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                    "No existe despacho para transaccionId: " + transaccionId);
        } catch (RestClientException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "ORQUESTADOR_NO_DISPONIBLE",
                    "No fue posible consultar el orquestador");
        }

        Map<String, Object> inventario = clients.reserva(transaccionId);
        Map<String, Object> transporte = clients.retiro(transaccionId);
        Map<String, Object> tracking = clients.tracking(transaccionId);

        boolean degradado = Boolean.FALSE.equals(inventario.get("disponible"))
                || Boolean.FALSE.equals(transporte.get("disponible"))
                || Boolean.FALSE.equals(tracking.get("disponible"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("transaccionId", transaccionId);
        body.put("degradado", degradado);
        body.put("despacho", despacho);
        body.put("inventario", inventario);
        body.put("transporte", transporte);
        body.put("tracking", tracking);
        body.put("generadoEn", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", detail);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
