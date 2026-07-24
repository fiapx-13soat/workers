package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.config.WorkersProperties;
import br.com.fiapx.workers.domain.event.OutboundEvent;
import br.com.fiapx.workers.domain.port.EventPublisher;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica eventos de resultado no exchange {@code video.processing}, envolvidos no envelope
 * padrão, com routing key derivada do tipo do evento e mensagem persistente.
 */
@Component
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EventEnvelopeCodec codec;
    private final String exchange;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec, WorkersProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        this.exchange = props.rabbit().exchange();
    }

    @Override
    public void publish(OutboundEvent event, String correlationId) {
        byte[] body = codec.encode(event, correlationId);
        String routingKey = RoutingKeys.forEvent(event);

        Message message = MessageBuilder.withBody(body)
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setMessageId(UUID.randomUUID().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("eventType", event.eventType())
                .setCorrelationId(correlationId)
                .build();

        rabbitTemplate.send(exchange, routingKey, message);
    }
}
