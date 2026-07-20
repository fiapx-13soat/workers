package br.com.fiapx.workers.domain.model;

/**
 * Unidade de trabalho já resolvida a partir de {@code ProcessingRequested}: o que o worker
 * precisa para processar. Sem estado mutável — o estado permanente é do Core.
 */
public record Job(
        String jobId,
        String ownerId,
        String videoStorageKey,
        ProcessingParameters parameters,
        String correlationId
) {

    public Job {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId obrigatório");
        }
        if (videoStorageKey == null || videoStorageKey.isBlank()) {
            throw new IllegalArgumentException("videoStorageKey obrigatório");
        }
    }

    /** Chave do ZIP de resultado no bucket de archives. */
    public String archiveStorageKey() {
        return "archives/" + jobId + ".zip";
    }

    /** Chave do marker de idempotência (indica job já concluído). */
    public String doneMarkerKey() {
        return "markers/" + jobId + ".done";
    }
}
