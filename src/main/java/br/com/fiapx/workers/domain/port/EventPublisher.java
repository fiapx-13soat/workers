package br.com.fiapx.workers.domain.port;

import br.com.fiapx.workers.domain.event.OutboundEvent;

/**
 * Publica eventos de resultado no broker. O adapter envolve o payload no envelope padrão,
 * mapeia {@code eventType} → routing key e propaga o {@code correlationId}.
 */
public interface EventPublisher {

    void publish(OutboundEvent event, String correlationId);
}
