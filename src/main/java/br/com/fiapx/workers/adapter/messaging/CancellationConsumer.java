package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.adapter.messaging.wire.ProcessingCancelledMessage;
import br.com.fiapx.workers.domain.port.CancellationRegistry;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code ProcessingCancelled} da fila exclusiva de cancelamento (broadcast por instância)
 * e marca o job no {@link CancellationRegistry}. O use case checa a flag em pontos seguros.
 */
@Component
public class CancellationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CancellationConsumer.class);

    private final CancellationRegistry registry;
    private final EventEnvelopeCodec codec;

    public CancellationConsumer(CancellationRegistry registry, EventEnvelopeCodec codec) {
        this.registry = registry;
        this.codec = codec;
    }

    @RabbitListener(queues = "#{workerCancellationQueue.name}")
    public void onCancellation(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            EventEnvelopeCodec.Decoded decoded = codec.decode(message.getBody());
            ProcessingCancelledMessage wire = codec.toPayload(decoded.payload(), ProcessingCancelledMessage.class);

            if (wire.jobId() != null) {
                registry.markCancelled(wire.jobId());
                log.info("Cancelamento registrado para job {}", wire.jobId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // cancelamento malformado não deve reciclar indefinidamente
            log.warn("Cancelamento inválido descartado: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
