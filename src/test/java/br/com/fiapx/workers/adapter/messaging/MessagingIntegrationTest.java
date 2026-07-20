package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.application.ProcessVideoUseCase;
import br.com.fiapx.workers.domain.event.ArchiveReady;
import br.com.fiapx.workers.domain.event.ProcessingFailed;
import br.com.fiapx.workers.domain.event.ProcessingRequested;
import br.com.fiapx.workers.domain.event.ProcessingStarted;
import br.com.fiapx.workers.domain.port.CancellationRegistry;
import br.com.fiapx.workers.domain.port.EventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class MessagingIntegrationTest {

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.addresses",
                () -> rabbit.getHost() + ":" + rabbit.getAmqpPort());
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    EventPublisher publisher;
    @Autowired
    CancellationRegistry cancellationRegistry;
    @Autowired
    RecordingUseCase recordingUseCase;

    private final ObjectMapper json = new ObjectMapper();

    private static final String EXCHANGE = "video.processing";
    private static final String RESULTS_QUEUE = "test.results";

    // ---- Consumo de job ----

    @Test
    void consomeProcessingRequestedEDelegaAoUseCase() throws Exception {
        recordingUseCase.reset();
        String body = """
                {"eventType":"ProcessingRequested","schemaVersion":1,"eventId":"e1",
                 "occurredAt":"2026-07-20T10:00:00Z","correlationId":"corr-1",
                 "payload":{"jobId":"job-1","videoStorageKey":"videos/job-1.mp4",
                            "parameters":{"fps":2},"ownerId":"owner-1"}}""";
        rabbitTemplate.convertAndSend(EXCHANGE, RoutingKeys.JOB_REQUESTED, body.getBytes());

        assertTrue(recordingUseCase.await(10), "use case não foi invocado");
        ProcessingRequested req = recordingUseCase.last.get();
        assertEquals("job-1", req.jobId());
        assertEquals("videos/job-1.mp4", req.videoStorageKey());
        assertEquals(2, req.parameters().fps());
        assertEquals("corr-1", recordingUseCase.lastCorrelationId.get());
    }

    @Test
    void toleraParametersVazioResolvendoParaNull() throws Exception {
        recordingUseCase.reset();
        String body = """
                {"eventType":"ProcessingRequested","schemaVersion":1,"eventId":"e2",
                 "occurredAt":"2026-07-20T10:00:00Z","correlationId":"corr-2",
                 "payload":{"jobId":"job-2","videoStorageKey":"videos/job-2.mp4",
                            "parameters":{},"ownerId":"owner-2"}}""";
        rabbitTemplate.convertAndSend(EXCHANGE, RoutingKeys.JOB_REQUESTED, body.getBytes());

        assertTrue(recordingUseCase.await(10));
        assertNull(recordingUseCase.last.get().parameters(),
                "parameters vazio deve virar null (default resolvido no domínio)");
    }

    // ---- Publicação ----

    @Test
    void publicaProcessingFailedComEnvelopeECampoTransient() throws Exception {
        publisher.publish(
                new ProcessingFailed("job-9", "BAD_VIDEO", "Não foi possível processar o vídeo.", false),
                "corr-9");

        JsonNode env = receiveAsJson();
        assertEquals("ProcessingFailed", env.get("eventType").asText());
        assertEquals(1, env.get("schemaVersion").asInt());
        assertEquals("corr-9", env.get("correlationId").asText());

        JsonNode payload = env.get("payload");
        assertEquals("job-9", payload.get("jobId").asText());
        assertEquals("BAD_VIDEO", payload.get("errorCode").asText());
        assertTrue(payload.has("transient"), "deve serializar 'transient', não 'transientError'");
        assertFalse(payload.get("transient").asBoolean());
        assertFalse(payload.has("transientError"));
    }

    @Test
    void publicaProcessingStartedEArchiveReadyNasRoutingKeysCertas() throws Exception {
        publisher.publish(new ProcessingStarted("job-10"), "corr-10");
        JsonNode started = receiveAsJson();
        assertEquals("ProcessingStarted", started.get("eventType").asText());
        assertEquals("job-10", started.get("payload").get("jobId").asText());

        publisher.publish(new ArchiveReady("job-11", "archives/job-11.zip", 2048), "corr-11");
        JsonNode archive = receiveAsJson();
        assertEquals("ArchiveReady", archive.get("eventType").asText());
        assertEquals("archives/job-11.zip", archive.get("payload").get("archiveStorageKey").asText());
        assertEquals(2048, archive.get("payload").get("sizeBytes").asLong());
    }

    // ---- Cancelamento (broadcast) ----

    @Test
    void consomeProcessingCancelledEMarcaRegistry() throws Exception {
        String jobId = "job-cancel-" + UUID.randomUUID();
        String body = String.format("""
                {"eventType":"ProcessingCancelled","schemaVersion":1,"eventId":"ec",
                 "occurredAt":"%s","correlationId":"corr-c",
                 "payload":{"jobId":"%s"}}""", Instant.now(), jobId);
        rabbitTemplate.convertAndSend(EXCHANGE, RoutingKeys.JOB_CANCELLED, body.getBytes());

        boolean marked = false;
        for (int i = 0; i < 50 && !marked; i++) {
            marked = cancellationRegistry.isCancelled(jobId);
            if (!marked) Thread.sleep(100);
        }
        assertTrue(marked, "cancelamento não foi registrado");
    }

    private JsonNode receiveAsJson() throws Exception {
        Message msg = rabbitTemplate.receive(RESULTS_QUEUE, 5000);
        assertNotNull(msg, "nenhuma mensagem publicada recebida");
        return json.readTree(msg.getBody());
    }

    // ---- Beans de teste ----

    @TestConfiguration
    static class TestBeans {

        @Bean
        @org.springframework.context.annotation.Primary
        RecordingUseCase recordingUseCase() {
            return new RecordingUseCase();
        }

        /** Fila durável de jobs (no ambiente real é declarada pelo fiapx-infra). */
        @Bean
        Declarables jobsTopology(TopicExchange videoProcessingExchange) {
            Queue jobs = QueueBuilder.durable("q.workers.jobs").build();
            Queue results = QueueBuilder.durable(RESULTS_QUEUE).build();
            Binding jobsBinding = BindingBuilder.bind(jobs).to(videoProcessingExchange)
                    .with(RoutingKeys.JOB_REQUESTED);
            // captura de todos os eventos de resultado publicados
            Binding started = BindingBuilder.bind(results).to(videoProcessingExchange).with(RoutingKeys.JOB_STARTED);
            Binding completed = BindingBuilder.bind(results).to(videoProcessingExchange).with(RoutingKeys.JOB_COMPLETED);
            Binding failed = BindingBuilder.bind(results).to(videoProcessingExchange).with(RoutingKeys.JOB_FAILED);
            Binding archive = BindingBuilder.bind(results).to(videoProcessingExchange).with(RoutingKeys.ARCHIVE_READY);
            return new Declarables(jobs, results, jobsBinding, started, completed, failed, archive);
        }
    }

    /** Captura as chamadas do consumer sem exercer o processamento real (que é do passo 6). */
    static class RecordingUseCase implements ProcessVideoUseCase {
        final AtomicReference<ProcessingRequested> last = new AtomicReference<>();
        final AtomicReference<String> lastCorrelationId = new AtomicReference<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        void reset() {
            last.set(null);
            lastCorrelationId.set(null);
            latch = new CountDownLatch(1);
        }

        boolean await(int seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }

        @Override
        public void handle(ProcessingRequested request, String correlationId) {
            last.set(request);
            lastCorrelationId.set(correlationId);
            latch.countDown();
        }
    }
}
