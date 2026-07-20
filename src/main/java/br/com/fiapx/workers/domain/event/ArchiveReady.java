package br.com.fiapx.workers.domain.event;

/**
 * Publicado (routing key {@code archive.ready}) após o ZIP dos frames ser gravado no bucket
 * de archives. O Core usa {@code archiveStorageKey} para gerar a pre-signed URL de download.
 */
public record ArchiveReady(String jobId, String archiveStorageKey, long sizeBytes) implements OutboundEvent {

    @Override
    public String eventType() {
        return EventTypes.ARCHIVE_READY;
    }
}
