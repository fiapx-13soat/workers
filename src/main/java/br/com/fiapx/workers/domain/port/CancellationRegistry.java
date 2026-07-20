package br.com.fiapx.workers.domain.port;

/**
 * Registro thread-safe de jobs cancelados. O consumer de {@code ProcessingCancelled} marca;
 * o use case consulta em pontos seguros e limpa ao finalizar.
 */
public interface CancellationRegistry {

    void markCancelled(String jobId);

    boolean isCancelled(String jobId);

    void clear(String jobId);
}
