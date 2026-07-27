package br.com.fiapx.workers.adapter.messaging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import br.com.fiapx.workers.adapter.messaging.wire.ProcessingRequestedMessage;
import br.com.fiapx.workers.adapter.observability.WorkerMetrics;
import br.com.fiapx.workers.application.ProcessVideoUseCase;
import br.com.fiapx.workers.domain.event.ProcessingRequested;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.model.ProcessingParameters;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code ProcessingRequested} de {@code q.workers.jobs} (ack manual, prefetch=1),
 * decodifica, seta o MDC e delega ao use case. Toda mensagem termina com {@code ack} — o
 * reenfileiramento (backoff) e a DLQ são feitos por <b>republicação explícita</b> via
 * {@link JobFailureHandler}, dando controle total sobre o backoff variável por tentativa.
 *
 * <p>Nota sobre CA-W08: um {@code kill -9} antes do {@code ack} deixa a mensagem não-ackeada,
 * que o broker redistribui a outro worker — nada se perde.
 */
@Component
public class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final ProcessVideoUseCase useCase;
    private final EventEnvelopeCodec codec;
    private final JobFailureHandler failureHandler;
    private final WorkerMetrics metrics;

    public JobConsumer(
            ProcessVideoUseCase useCase,
            EventEnvelopeCodec codec,
            JobFailureHandler failureHandler,
            WorkerMetrics metrics) {
        this.useCase = useCase;
        this.codec = codec;
        this.failureHandler = failureHandler;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${workers.rabbit.queue-jobs}")
    public void onJob(Message message, Channel channel) throws IOException {
        long start = System.currentTimeMillis();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int attempt = failureHandler.attemptOf(message);
        String jobId = null;
        String correlationId = null;
        String outcome = "ok";

        try {
            // falha na decodificação = mensagem malformada → DLQ, sem retry
            ProcessingRequested request;
            try {
                EventEnvelopeCodec.Decoded decoded = codec.decode(message.getBody());
                correlationId = decoded.correlationId();
                ProcessingRequestedMessage wire = codec.toPayload(decoded.payload(), ProcessingRequestedMessage.class);
                request = toDomain(wire);
            } catch (MessageDecodingException | IllegalArgumentException bad) {
                jobId = tryExtractJobId(message);
                correlationId = tryExtractCorrelationId(message);
                outcome = "malformed";
                metrics.malformed();
                failureHandler.onMalformedMessage(message, jobId, correlationId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            jobId = request.jobId();
            MDC.put("correlationId", correlationId);
            MDC.put("jobId", jobId);
            Timer.Sample sample = metrics.startProcessing();
            try {
                useCase.handle(request, correlationId);
                metrics.completed(sample);
                channel.basicAck(deliveryTag, false);
            } catch (ProcessingException pe) {
                outcome = "failed";
                metrics.failed(sample);
                failureHandler.onProcessingFailure(
                        message, attempt, jobId, correlationId, pe.errorCode(), pe.friendlyMessage(), pe.isTransient());
                channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
                // inesperado → tratado como transitório (dá chance de retry)
                outcome = "failed";
                metrics.failed(sample);
                log.error("Erro inesperado no job {}: {}", jobId, e.getMessage(), e);
                failureHandler.onProcessingFailure(
                        message, attempt, jobId, correlationId, "INTERNAL", "Erro interno ao processar o vídeo.", true);
                channel.basicAck(deliveryTag, false);
            } finally {
                MDC.clear();
            }
        } finally {
            // Um evento canônico (wide event) por job consumido. jobId/correlationId explícitos
            // porque o MDC já foi limpo; trace_id vem do agente OTel no encode.
            log.info(
                    "job consumido",
                    kv("event", "job_consumed"),
                    kv("jobId", jobId),
                    kv("correlationId", correlationId),
                    kv("attempt", attempt),
                    kv("outcome", outcome),
                    kv("durationMs", System.currentTimeMillis() - start));
        }
    }

    private ProcessingRequested toDomain(ProcessingRequestedMessage wire) {
        ProcessingParameters parameters = null;
        if (wire.parameters() != null && wire.parameters().fps() != null) {
            parameters = new ProcessingParameters(wire.parameters().fps()); // valida fps > 0
        }
        return new ProcessingRequested(wire.jobId(), wire.videoStorageKey(), parameters, wire.ownerId());
    }

    /** Tentativa best-effort de recuperar o jobId de uma mensagem malformada (para notificar). */
    private String tryExtractJobId(Message message) {
        try {
            return codec.toPayload(codec.decode(message.getBody()).payload(), ProcessingRequestedMessage.class)
                    .jobId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String tryExtractCorrelationId(Message message) {
        try {
            return codec.decode(message.getBody()).correlationId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
