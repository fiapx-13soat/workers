package br.com.fiapx.workers.domain.event;

/**
 * Publicado (routing key {@code job.completed}) ao concluir a extração com sucesso.
 */
public record ProcessingCompleted(String jobId, int frameCount) implements OutboundEvent {

    @Override
    public String eventType() {
        return EventTypes.PROCESSING_COMPLETED;
    }
}
