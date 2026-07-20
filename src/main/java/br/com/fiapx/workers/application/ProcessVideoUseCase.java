package br.com.fiapx.workers.application;

import br.com.fiapx.workers.domain.event.ProcessingRequested;

/**
 * Orquestra o processamento de um vídeo a partir de {@code ProcessingRequested}.
 * Implementação concreta no passo 6; o consumer de mensageria depende desta abstração.
 */
public interface ProcessVideoUseCase {

    void handle(ProcessingRequested request, String correlationId);
}
