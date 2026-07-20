package br.com.fiapx.workers.domain.port;

import java.nio.file.Path;

/**
 * Acesso de escrita/leitura ao bucket de archives (destino). Implementação: S3 (AWS SDK v2).
 * Também suporta o marker de idempotência por job.
 */
public interface ArchiveStorage {

    /** Sobe o ZIP dos frames para a chave informada. Retorna o tamanho em bytes gravado. */
    long upload(Path zipFile, String storageKey);

    /** Verifica se um objeto existe (marker de idempotência ou o próprio archive). */
    boolean exists(String storageKey);

    /** Grava um marker vazio indicando conclusão do job (idempotência). */
    void writeMarker(String markerKey);

    /** Remove um objeto (limpeza de artefatos parciais em cancelamento). */
    void delete(String storageKey);
}
