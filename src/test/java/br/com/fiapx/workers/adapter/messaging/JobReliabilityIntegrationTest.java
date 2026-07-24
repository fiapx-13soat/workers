package br.com.fiapx.workers.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.fiapx.workers.application.ProcessVideoUseCase;
import br.com.fiapx.workers.domain.event.ProcessingRequested;
import br.com.fiapx.workers.domain.model.ProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Confiabilidade do consumo contra RabbitMQ real: backoff/retry, DLQ e ProcessingFailed.
 * Delays curtos ({@code 300,300}) para o teste ser rápido mas exercitar o caminho do broker.
 */
@Testcontainers
@SpringBootTest
class JobReliabilityIntegrationTest {

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.addresses", () -> rabbit.getHost() + ":" + rabbit.getAmqpPort());
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("workers.retry.delays-ms", () -> "300,300"); // 2 retentativas rápidas
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ControllableUseCase useCase;

    private final ObjectMapper json = new ObjectMapper();

    private static final String EXCHANGE = "video.processing";
    private static final String DLQ = "q.workers.jobs.dlq";
    private static final String RESULTS = "test.results";

    @BeforeEach
    void reset() {
        useCase.reset();
        drain(DLQ);
        drain(RESULTS);
    }

    @Test
    void falhaTransitoriaRetentaEDepoisConclui() throws Exception {
        useCase.failTransientTimes(1); // falha 1x, depois sucesso
        sendJob("job-retry-ok");

        assertTrue(useCase.awaitSuccess(15), "job deveria concluir após retry");
        assertEquals(2, useCase.attempts.get(), "1 falha + 1 sucesso");
        Thread.sleep(500);
        assertNull(rabbitTemplate.receive(DLQ), "não deve ir para DLQ");
    }

    @Test
    void falhaDeterministicaVaiDiretoParaDlqComProcessingFailed() throws Exception {
        useCase.failDeterministic();
        sendJob("job-bad");

        Message dead = awaitMessage(DLQ);
        assertNotNull(dead, "mensagem deveria estar na DLQ");
        assertEquals(1, useCase.attempts.get(), "determinística não retenta");

        JsonNode failed = awaitFailedEvent();
        assertEquals("job-bad", failed.get("payload").get("jobId").asText());
        assertFalse(failed.get("payload").get("transient").asBoolean());
    }

    @Test
    void falhaTransitoriaEsgotaTentativasEVaiParaDlq() throws Exception {
        useCase.failTransientTimes(Integer.MAX_VALUE); // sempre falha
        sendJob("job-exhaust");

        Message dead = awaitMessage(DLQ);
        assertNotNull(dead, "após esgotar retries deve ir para DLQ");
        assertEquals(3, useCase.attempts.get(), "1 inicial + 2 retentativas");

        JsonNode failed = awaitFailedEvent();
        assertEquals("job-exhaust", failed.get("payload").get("jobId").asText());
        assertTrue(failed.get("payload").get("transient").asBoolean());
    }

    @Test
    void mensagemMalformadaVaiParaDlqSemProcessar() throws Exception {
        rabbitTemplate.convertAndSend(EXCHANGE, RoutingKeys.JOB_REQUESTED, "{lixo".getBytes());

        Message dead = awaitMessage(DLQ);
        assertNotNull(dead, "mensagem malformada deve ir para DLQ");
        assertEquals(0, useCase.attempts.get(), "use case nunca é chamado");
    }

    // ---- helpers ----

    private void sendJob(String jobId) {
        String body = String.format(
                """
                {"eventType":"ProcessingRequested","schemaVersion":1,"eventId":"e",
                 "occurredAt":"2026-07-20T10:00:00Z","correlationId":"corr-%s",
                 "payload":{"jobId":"%s","videoStorageKey":"videos/%s.mp4","ownerId":"o1"}}""",
                jobId, jobId, jobId);
        rabbitTemplate.convertAndSend(EXCHANGE, RoutingKeys.JOB_REQUESTED, body.getBytes());
    }

    private Message awaitMessage(String queue) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Message m = rabbitTemplate.receive(queue); // basicGet não-bloqueante
            if (m != null) return m;
            Thread.sleep(200);
        }
        return null;
    }

    private JsonNode awaitFailedEvent() throws Exception {
        Message m = awaitMessage(RESULTS);
        assertNotNull(m, "ProcessingFailed não publicado");
        return json.readTree(m.getBody());
    }

    private void drain(String queue) {
        while (rabbitTemplate.receive(queue) != null) {
            // esvazia
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        ControllableUseCase controllableUseCase() {
            return new ControllableUseCase();
        }

        /** Filas duráveis que no ambiente real são declaradas pelo fiapx-infra. */
        @Bean
        Declarables reliabilityTopology(TopicExchange videoProcessingExchange) {
            Queue jobs = QueueBuilder.durable("q.workers.jobs").build();
            Queue retry = QueueBuilder.durable("q.workers.jobs.retry")
                    .deadLetterExchange("")
                    .deadLetterRoutingKey("q.workers.jobs")
                    .build();
            Queue dlq = QueueBuilder.durable(DLQ).build();
            Queue results = QueueBuilder.durable(RESULTS).build();
            Binding jobsBinding =
                    BindingBuilder.bind(jobs).to(videoProcessingExchange).with(RoutingKeys.JOB_REQUESTED);
            Binding failedBinding =
                    BindingBuilder.bind(results).to(videoProcessingExchange).with(RoutingKeys.JOB_FAILED);
            return new Declarables(jobs, retry, dlq, results, jobsBinding, failedBinding);
        }
    }

    /** Use case controlável para exercitar a política de falha do consumer. */
    static class ControllableUseCase implements ProcessVideoUseCase {
        enum Mode {
            SUCCESS,
            TRANSIENT,
            DETERMINISTIC
        }

        final AtomicInteger attempts = new AtomicInteger();
        volatile Mode mode = Mode.SUCCESS;
        volatile int transientFailures = 0;
        volatile CountDownLatch successLatch = new CountDownLatch(1);

        void reset() {
            attempts.set(0);
            mode = Mode.SUCCESS;
            transientFailures = 0;
            successLatch = new CountDownLatch(1);
        }

        void failTransientTimes(int times) {
            mode = Mode.TRANSIENT;
            transientFailures = times;
        }

        void failDeterministic() {
            mode = Mode.DETERMINISTIC;
        }

        boolean awaitSuccess(int seconds) throws InterruptedException {
            return successLatch.await(seconds, TimeUnit.SECONDS);
        }

        @Override
        public void handle(ProcessingRequested request, String correlationId) {
            int n = attempts.incrementAndGet();
            switch (mode) {
                case DETERMINISTIC -> throw ProcessingException.deterministicFailure(
                        "BAD_VIDEO", "Vídeo inválido.", null);
                case TRANSIENT -> {
                    if (n <= transientFailures) {
                        throw ProcessingException.transientFailure("S3_DOWNLOAD", "Falha temporária.", null);
                    }
                    successLatch.countDown();
                }
                case SUCCESS -> successLatch.countDown();
            }
        }
    }
}
