package br.com.fiapx.workers.adapter.messaging.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Representação de fio de {@code ProcessingRequested}. {@code fps} é {@link Integer} (nullable)
 * para tolerar {@code parameters} ausente/vazio — a resolução do default é do domínio.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessingRequestedMessage(
        String jobId,
        String videoStorageKey,
        Parameters parameters,
        String ownerId
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameters(Integer fps) {
    }
}
