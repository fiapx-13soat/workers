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
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia RabbitMQ do lado do worker.
 *
 * <p>As filas duráveis {@code q.workers.jobs}, {@code q.workers.jobs.retry} e
 * {@code q.workers.jobs.dlq} são declaradas pelo {@code fiapx-infra} (fonte única em
 * {@code definitions.json}) — o worker apenas as consome/publica, sem redeclarar. Redeclarar aqui
 * com argumento divergente derruba o serviço no boot com {@code 406 PRECONDITION_FAILED}.
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
    public TopicExchange videoProcessingExchange(WorkersProperties props) {
        return ExchangeBuilder.topicExchange(props.rabbit().exchange())
                .durable(true)
                .build();
    }

    /** Fila anônima, exclusiva e auto-delete: uma por instância de worker. */
    @Bean
    public Queue workerCancellationQueue() {
        return QueueBuilder.nonDurable().exclusive().autoDelete().build();
    }

    @Bean
    public Binding cancellationBinding(Queue workerCancellationQueue, TopicExchange videoProcessingExchange) {
        return BindingBuilder.bind(workerCancellationQueue)
                .to(videoProcessingExchange)
                .with(RoutingKeys.JOB_CANCELLED);
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
            WorkersProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setContainerCustomizer(container -> {
            container.setShutdownTimeout(props.shutdownTimeout().toMillis());
            container.setForceCloseChannel(true);
        });
        return factory;
    }
}
