package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.domain.event.ProcessingFailed;
import br.com.fiapx.workers.domain.port.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Política de falha do consumo de jobs:
 * <ul>
 *   <li>Falha <b>transitória</b> e ainda há tentativas → republica na retry queue com
 *       {@code expiration = backoff[tentativa]}; o broker devolve à fila principal após o delay.</li>
 *   <li>Falha <b>determinística</b> ou tentativas esgotadas → publica {@code ProcessingFailed}
 *       e envia a mensagem para a DLQ, sem travar a fila principal.</li>
 * </ul>
 * A contagem de tentativas viaja no header {@value #ATTEMPT_HEADER} (preservado no dead-lettering).
 */
@Component
public class JobFailureHandler {

    public static final String ATTEMPT_HEADER = "x-attempt";

    private static final Logger log = LoggerFactory.getLogger(JobFailureHandler.class);

    private final RabbitTemplate rabbitTemplate;
    private final EventPublisher publisher;
    private final long[] delaysMs;
    private final String retryQueue;
    private final String dlqQueue;

    public JobFailureHandler(RabbitTemplate rabbitTemplate,
                             EventPublisher publisher,
                             @Value("${workers.retry.delays-ms}") long[] delaysMs,
                             @Value("${workers.rabbit.queue-retry}") String retryQueue,
                             @Value("${workers.rabbit.queue-dlq}") String dlqQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.publisher = publisher;
        this.delaysMs = delaysMs;
        this.retryQueue = retryQueue;
        this.dlqQueue = dlqQueue;
    }

    /** Tentativa corrente (0 = primeira entrega). */
    public int attemptOf(Message message) {
        Object header = message.getMessageProperties().getHeader(ATTEMPT_HEADER);
        return header instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Falha durante o processamento de um job já decodificado.
     */
    public void onProcessingFailure(Message original, int attempt, String jobId, String correlationId,
                                    String errorCode, String friendlyMessage, boolean transientError) {
        if (transientError && attempt < delaysMs.length) {
            long delay = delaysMs[attempt];
            log.warn("Job {} falhou (transitória, tentativa {}); reenfileirando em {}ms",
                    jobId, attempt + 1, delay);
            republishToRetry(original, attempt + 1, delay, correlationId);
        } else {
            log.error("Job {} para DLQ ({}): {}", jobId,
                    transientError ? "tentativas esgotadas" : "falha determinística", errorCode);
            if (jobId != null) {
                publisher.publish(
                        new ProcessingFailed(jobId, errorCode, friendlyMessage, transientError),
                        correlationId);
            }
            republishToDlq(original, errorCode);
        }
    }

    /**
     * Mensagem malformada (não decodificável): vai direto para a DLQ. Se ao menos o {@code jobId}
     * foi extraído, também publica {@code ProcessingFailed} para o usuário não ficar no escuro.
     */
    public void onMalformedMessage(Message original, String jobId, String correlationId) {
        log.warn("Mensagem malformada para DLQ (jobId={})", jobId);
        if (jobId != null) {
            publisher.publish(
                    new ProcessingFailed(jobId, "INVALID_MESSAGE",
                            "Não foi possível interpretar a solicitação de processamento.", false),
                    correlationId);
        }
        republishToDlq(original, "INVALID_MESSAGE");
    }

    private void republishToRetry(Message original, int nextAttempt, long delayMs, String correlationId) {
        Message message = MessageBuilder.withBody(original.getBody())
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setCorrelationId(correlationId)
                .setHeader(ATTEMPT_HEADER, nextAttempt)
                .setExpiration(Long.toString(delayMs))
                .build();
        rabbitTemplate.send("", retryQueue, message);
    }

    private void republishToDlq(Message original, String errorCode) {
        Message message = MessageBuilder.withBody(original.getBody())
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-error-code", errorCode)
                .copyHeadersIfAbsent(original.getMessageProperties().getHeaders())
                .build();
        rabbitTemplate.send("", dlqQueue, message);
    }
}
