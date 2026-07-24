package br.com.fiapx.workers.domain.event;

/**
 * Publicado (routing key {@code job.failed}) quando o processamento falha de forma determinística
 * (ex.: vídeo corrompido). {@code errorMessage} é <b>amigável</b> — o Notification o repassa ao
 * usuário; nunca stack trace. {@code transientError} indica se a causa era transitória.
 */
public record ProcessingFailed(String jobId, String errorCode, String errorMessage, boolean transientError)
        implements OutboundEvent {

    @Override
    public String eventType() {
        return EventTypes.PROCESSING_FAILED;
    }
}
