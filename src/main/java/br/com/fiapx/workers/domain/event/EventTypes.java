package br.com.fiapx.workers.domain.event;

/**
 * Nomes de {@code eventType} do contrato e a versão de schema corrente.
 * Mudança de payload de qualquer evento exige bump de {@link #SCHEMA_VERSION} e PR conjunto.
 */
public final class EventTypes {

    public static final int SCHEMA_VERSION = 1;

    // Consumidos
    public static final String PROCESSING_REQUESTED = "ProcessingRequested";
    public static final String PROCESSING_CANCELLED = "ProcessingCancelled";

    // Publicados
    public static final String PROCESSING_STARTED = "ProcessingStarted";
    public static final String PROCESSING_COMPLETED = "ProcessingCompleted";
    public static final String ARCHIVE_READY = "ArchiveReady";
    public static final String PROCESSING_FAILED = "ProcessingFailed";

    private EventTypes() {
    }
}
