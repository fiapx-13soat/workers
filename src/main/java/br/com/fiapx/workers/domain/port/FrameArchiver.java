package br.com.fiapx.workers.domain.port;

import java.nio.file.Path;

/**
 * Empacota os frames extraídos em um arquivo ZIP local. Implementação: {@code java.util.zip}
 * (portado do {@code createZipFile} do projeto base).
 */
public interface FrameArchiver {

    /**
     * Compacta todos os arquivos regulares de {@code framesDirectory} em {@code zipTarget}.
     *
     * @return número de entradas escritas no ZIP
     */
    int archive(Path framesDirectory, Path zipTarget);
}
