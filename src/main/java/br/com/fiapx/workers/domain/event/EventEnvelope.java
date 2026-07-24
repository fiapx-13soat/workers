package br.com.fiapx.workers.domain.event;

import java.time.Instant;

/**
 * Envelope padrão de todos os eventos do sistema (contrato compartilhado entre os 3 serviços):
 * {@code {eventType, schemaVersion, eventId, occurredAt, correlationId, payload}}.
 *
 * @param <T> tipo do payload específico do evento
 */
public record EventEnvelope<T>(
        String eventType, int schemaVersion, String eventId, Instant occurredAt, String correlationId, T payload) {}
