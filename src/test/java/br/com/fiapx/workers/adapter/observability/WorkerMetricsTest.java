package br.com.fiapx.workers.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class WorkerMetricsTest {

    private SimpleMeterRegistry registry;
    private RabbitAdmin rabbitAdmin;
    private WorkerMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        rabbitAdmin = mock(RabbitAdmin.class);
        metrics = new WorkerMetrics(registry, rabbitAdmin, "q.workers.jobs");
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void processingGaugeSobeEDesceComOJob() {
        assertEquals(0, gauge("workers.jobs.processing"));

        Timer.Sample sample = metrics.startProcessing();
        assertEquals(1, gauge("workers.jobs.processing"));

        metrics.completed(sample);
        assertEquals(0, gauge("workers.jobs.processing"));
        assertEquals(1, registry.get("workers.jobs.completed").counter().count());
        assertEquals(1, registry.get("workers.job.duration").timer().count());
    }

    @Test
    void falhaIncrementaContadorEZeraProcessing() {
        Timer.Sample sample = metrics.startProcessing();
        metrics.failed(sample);

        assertEquals(0, gauge("workers.jobs.processing"));
        assertEquals(1, registry.get("workers.jobs.failed").counter().count());
        assertEquals(1, registry.get("workers.job.duration").timer().count());
    }

    @Test
    void malformedIncrementaFailedSemDuracao() {
        metrics.malformed();
        assertEquals(1, registry.get("workers.jobs.failed").counter().count());
        assertEquals(0, registry.get("workers.job.duration").timer().count());
    }

    @Test
    void queueDepthRefleteOBroker() {
        when(rabbitAdmin.getQueueInfo("q.workers.jobs")).thenReturn(new QueueInformation("q.workers.jobs", 7, 2));
        assertEquals(7, gauge("workers.queue.depth"));
    }

    @Test
    void queueDepthZeroQuandoFilaAusente() {
        when(rabbitAdmin.getQueueInfo("q.workers.jobs")).thenReturn(null);
        assertEquals(0, gauge("workers.queue.depth"));
    }
}
