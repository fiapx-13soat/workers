package br.com.fiapx.workers.domain.model;

/**
 * Sinaliza que o job foi cancelado durante a execução. Não é uma falha: o worker aborta,
 * limpa artefatos parciais e <b>não</b> publica {@code ProcessingCompleted} (CA-W06).
 */
public class CancelledException extends RuntimeException {

    private final String jobId;

    public CancelledException(String jobId) {
        super("job cancelado: " + jobId);
        this.jobId = jobId;
    }

    /** Aborto sinalizado em camada que não conhece o jobId (ex.: extractor); o use case o correlaciona. */
    public CancelledException() {
        this(null);
    }

    public String jobId() {
        return jobId;
    }
}
