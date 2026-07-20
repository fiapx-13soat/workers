package br.com.fiapx.workers.domain.event;

import br.com.fiapx.workers.domain.model.ProcessingParameters;

/**
 * Payload de {@code ProcessingRequested} (routing key {@code job.requested}), consumido de
 * {@code q.workers.jobs}. {@code parameters} pode ser {@code null} — nesse caso o worker aplica
 * {@code DEFAULT_FPS}.
 */
public record ProcessingRequested(
        String jobId,
        String videoStorageKey,
        ProcessingParameters parameters,
        String ownerId
) {
}
