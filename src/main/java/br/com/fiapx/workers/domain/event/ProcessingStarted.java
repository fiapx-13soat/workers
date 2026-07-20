package br.com.fiapx.workers.domain.event;

/**
 * Publicado (routing key {@code job.started}) <b>antes</b> de iniciar a extração,
 * para que o usuário veja o status {@code PROCESSING} (CA-W01).
 */
public record ProcessingStarted(String jobId) implements OutboundEvent {

    @Override
    public String eventType() {
        return EventTypes.PROCESSING_STARTED;
    }
}
