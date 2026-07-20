package br.com.fiapx.workers.domain.event;

/**
 * Evento publicado por este serviço. A interface selada permite ao adapter de mensageria
 * mapear {@link #eventType()} → routing key de forma exaustiva (switch sem {@code default}).
 */
public sealed interface OutboundEvent
        permits ProcessingStarted, ProcessingCompleted, ArchiveReady, ProcessingFailed {

    /** Nome do evento (ver {@link EventTypes}). */
    String eventType();

    /** Job ao qual o evento se refere (usado em log/MDC). */
    String jobId();
}
