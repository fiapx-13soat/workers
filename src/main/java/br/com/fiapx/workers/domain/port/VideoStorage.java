package br.com.fiapx.workers.domain.port;

import java.nio.file.Path;

/**
 * Acesso de leitura ao bucket de vídeos (origem). Implementação: S3 (AWS SDK v2).
 */
public interface VideoStorage {

    /**
     * Baixa o vídeo para um arquivo dentro do diretório informado.
     *
     * @param storageKey      chave do objeto no bucket de vídeos
     * @param targetDirectory diretório temporário de destino
     * @return caminho do arquivo baixado
     */
    Path download(String storageKey, Path targetDirectory);
}
