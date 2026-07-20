package br.com.fiapx.workers.adapter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Métricas expostas em {@code /metrics} (Prometheus), conforme CA-W09:
 * <ul>
 *   <li>{@code workers_jobs_processing} — gauge de jobs em execução nesta réplica.</li>
 *   <li>{@code workers_job_duration_seconds} — histograma de duração do processamento.</li>
 *   <li>{@code workers_queue_depth} — profundidade da fila de jobs (lida do broker).</li>
 *   <li>{@code workers_jobs_completed_total} / {@code workers_jobs_failed_total} — contadores.</li>
 * </ul>
 */
@Component
public class WorkerMetrics {

    private final AtomicInteger processing = new AtomicInteger();
    private final MeterRegistry registry;
    private final Timer duration;
    private final Counter completed;
    private final Counter failed;

    public WorkerMetrics(MeterRegistry registry,
                         RabbitAdmin rabbitAdmin,
                         @Value("${workers.rabbit.queue-jobs}") String jobsQueue) {
        this.registry = registry;

        Gauge.builder("workers.jobs.processing", processing, AtomicInteger::get)
                .description("Jobs em processamento nesta réplica")
                .register(registry);

        this.duration = Timer.builder("workers.job.duration")
                .description("Duração do processamento de um job")
                .publishPercentileHistogram()
                .register(registry);

        this.completed = Counter.builder("workers.jobs.completed")
                .description("Jobs processados com sucesso")
                .register(registry);

        this.failed = Counter.builder("workers.jobs.failed")
                .description("Jobs que falharam (nesta tentativa)")
                .register(registry);

        Gauge.builder("workers.queue.depth", () -> queueDepth(rabbitAdmin, jobsQueue))
                .description("Mensagens pendentes na fila de jobs")
                .register(registry);
    }

    /** Marca o início do processamento de um job e retorna a amostra do timer. */
    public Timer.Sample startProcessing() {
        processing.incrementAndGet();
        return Timer.start(registry);
    }

    public void completed(Timer.Sample sample) {
        sample.stop(duration);
        completed.increment();
        processing.decrementAndGet();
    }

    public void failed(Timer.Sample sample) {
        sample.stop(duration);
        failed.increment();
        processing.decrementAndGet();
    }

    /** Falha antes de processar (mensagem malformada): conta como falha, sem duração. */
    public void malformed() {
        failed.increment();
    }

    private double queueDepth(RabbitAdmin rabbitAdmin, String queue) {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(queue);
            return info == null ? 0 : info.getMessageCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
