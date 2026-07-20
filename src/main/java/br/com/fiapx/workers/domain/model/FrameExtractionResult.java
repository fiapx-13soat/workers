package br.com.fiapx.workers.domain.model;

import java.nio.file.Path;

/**
 * Resultado da extração de frames: quantidade extraída e o diretório temporário onde os
 * PNGs foram gravados (a serem zipados em seguida).
 */
public record FrameExtractionResult(int frameCount, Path framesDirectory) {

    public FrameExtractionResult {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("nenhum frame extraído");
        }
    }
}
