package br.com.fiapx.workers.interfaces;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Expõe {@code GET /ready} para uniformizar o probe de readiness com o fiapx-core e o
 * fiapx-notification, que servem essa rota. O worker não tem API própria; esta é a única rota
 * fora do actuator.
 *
 * <p>Delega ao {@link HealthEndpoint} do actuator em vez de checar dependências na mão: o
 * agregado de health já inclui o indicador do RabbitMQ, então {@code /ready} reflete a mesma
 * verdade que o {@code /health}, sem duplicar lógica. Responde 200 quando UP, 503 caso contrário —
 * o formato de corpo (`{"status": ...}`) casa com o dos outros dois serviços.
 */
@RestController
public class ReadinessController {

    private final HealthEndpoint healthEndpoint;

    public ReadinessController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean up = Status.UP.equals(healthEndpoint.health().getStatus());
        return ResponseEntity
                .status(up ? 200 : 503)
                .body(Map.of("status", up ? "ready" : "not_ready"));
    }
}
