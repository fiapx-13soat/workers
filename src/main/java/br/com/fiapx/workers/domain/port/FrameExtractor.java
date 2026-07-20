package br.com.fiapx.workers.domain.port;

import br.com.fiapx.workers.domain.model.FrameExtractionResult;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.model.ProcessingParameters;

import java.nio.file.Path;

/**
 * Extrai frames de um vídeo. Implementação de referência: ffmpeg via {@code ProcessBuilder}.
 */
public interface FrameExtractor {

    /**
     * @param videoFile         arquivo de vídeo local (já baixado do S3)
     * @param parameters        parâmetros efetivos (fps já resolvido)
     * @param cancellationCheck consultado em pontos seguros; se {@code true}, aborta limpando artefatos
     * @return resultado com contagem de frames e diretório dos PNGs
     * @throws ProcessingException classificada como transitória ou determinística
     * @throws CancelledException  se o cancelamento foi sinalizado durante a extração
     */
    FrameExtractionResult extract(Path videoFile, ProcessingParameters parameters,
                                  CancellationCheck cancellationCheck) throws ProcessingException;
}
