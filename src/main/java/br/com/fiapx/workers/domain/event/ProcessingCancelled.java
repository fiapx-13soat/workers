package br.com.fiapx.workers.domain.event;

/**
 * Payload de {@code ProcessingCancelled} (routing key {@code job.cancelled}).
 * Marca o {@code jobId} para aborto em ponto seguro se estiver em execução.
 */
public record ProcessingCancelled(String jobId) {}
