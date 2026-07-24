package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.domain.event.ArchiveReady;
import br.com.fiapx.workers.domain.event.OutboundEvent;
import br.com.fiapx.workers.domain.event.ProcessingCompleted;
import br.com.fiapx.workers.domain.event.ProcessingFailed;
import br.com.fiapx.workers.domain.event.ProcessingStarted;

/**
 * Routing keys do contrato (exchange {@code video.processing}, topic).
 */
public final class RoutingKeys {

    // Consumidas
    public static final String JOB_REQUESTED = "job.requested";
    public static final String JOB_CANCELLED = "job.cancelled";

    // Publicadas
    public static final String JOB_STARTED = "job.started";
    public static final String JOB_COMPLETED = "job.completed";
    public static final String ARCHIVE_READY = "archive.ready";
    public static final String JOB_FAILED = "job.failed";

    private RoutingKeys() {}

    /**
     * Mapeia o evento publicado para sua routing key. Switch exaustivo sobre a interface selada:
     * adicionar um novo {@link OutboundEvent} sem tratar aqui vira erro de compilação.
     */
    public static String forEvent(OutboundEvent event) {
        return switch (event) {
            case ProcessingStarted ignored -> JOB_STARTED;
            case ProcessingCompleted ignored -> JOB_COMPLETED;
            case ArchiveReady ignored -> ARCHIVE_READY;
            case ProcessingFailed ignored -> JOB_FAILED;
        };
    }
}
