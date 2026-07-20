package br.com.fiapx.workers.config;

import br.com.fiapx.workers.adapter.messaging.RoutingKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Topologia RabbitMQ do lado do worker.
 *
 * <p>A fila durável {@code q.workers.jobs} e suas DLQs são declaradas pelo {@code fiapx-infra}
 * (fonte única em {@code definitions.json}) — o worker apenas a consome, sem redeclarar.
 *
 * <p>Já a fila de <b>cancelamento</b> é exclusiva e efêmera por instância: cada réplica declara a
 * sua e a liga a {@code job.cancelled}, para que o sinal chegue por broadcast a <i>todas</i> as
 * réplicas (a que estiver processando o job precisa ver o cancelamento).
 */
@Configuration
public class RabbitConfig {

    /** Admin explícito: declara os beans de topologia e é usado pelas métricas (profundidade de fila). */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /** Exchange do contrato (topic, durável) — declaração idempotente, igual à do infra. */
    @Bean
    public TopicExchange videoProcessingExchange(@Value("${workers.rabbit.exchange}") String exchange) {
        return ExchangeBuilder.topicExchange(exchange).durable(true).build();
    }

    /** Fila anônima, exclusiva e auto-delete: uma por instância de worker. */
    @Bean
    public Queue workerCancellationQueue() {
        return QueueBuilder.nonDurable()
                .exclusive()
                .autoDelete()
                .build();
    }

    @Bean
    public Binding cancellationBinding(Queue workerCancellationQueue, TopicExchange videoProcessingExchange) {
        return BindingBuilder.bind(workerCancellationQueue)
                .to(videoProcessingExchange)
                .with(RoutingKeys.JOB_CANCELLED);
    }

    /**
     * Retry queue (detalhe de implementação do backoff do worker): sem consumidor, expiração por
     * mensagem (definida ao publicar) e DLX de volta à fila principal via default exchange.
     * Uma mensagem que falha é republicada aqui com {@code expiration=delay}; ao expirar, o broker
     * a devolve a {@code q.workers.jobs} — backoff sem bloquear thread de consumo.
     */
    @Bean
    public Queue workerJobsRetryQueue(@Value("${workers.rabbit.queue-retry}") String retryQueue,
                                      @Value("${workers.rabbit.queue-jobs}") String jobsQueue) {
        return QueueBuilder.durable(retryQueue)
                .deadLetterExchange("")            // default exchange
                .deadLetterRoutingKey(jobsQueue)   // volta para a fila principal
                .build();
    }

    /**
     * Container factory com <b>graceful shutdown</b> (CA-W10): em SIGTERM, para de receber novas
     * mensagens e aguarda o job atual até {@code WORKER_SHUTDOWN_TIMEOUT}; se estourar, força o
     * fechamento do canal → a mensagem não-ackeada volta à fila e outro worker a processa (CA-W08).
     * Mantém ack manual/prefetch vindos do {@code application.yml} via configurer.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${workers.shutdown-timeout:120s}") Duration shutdownTimeout) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setContainerCustomizer(container -> {
            container.setShutdownTimeout(shutdownTimeout.toMillis());
            container.setForceCloseChannel(true);
        });
        return factory;
    }
}
