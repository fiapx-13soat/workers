package br.com.fiapx.workers.application;

import br.com.fiapx.workers.config.WorkersProperties;
import br.com.fiapx.workers.domain.event.ArchiveReady;
import br.com.fiapx.workers.domain.event.ProcessingCompleted;
import br.com.fiapx.workers.domain.event.ProcessingRequested;
import br.com.fiapx.workers.domain.event.ProcessingStarted;
import br.com.fiapx.workers.domain.model.CancelledException;
import br.com.fiapx.workers.domain.model.FrameExtractionResult;
import br.com.fiapx.workers.domain.model.Job;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.model.ProcessingParameters;
import br.com.fiapx.workers.domain.port.ArchiveStorage;
import br.com.fiapx.workers.domain.port.CancellationRegistry;
import br.com.fiapx.workers.domain.port.EventPublisher;
import br.com.fiapx.workers.domain.port.FrameArchiver;
import br.com.fiapx.workers.domain.port.FrameExtractor;
import br.com.fiapx.workers.domain.port.VideoStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Fluxo de processamento de um vídeo, orquestrando os ports:
 * <pre>
 * idempotência (marker) → ProcessingStarted → download → extração → zip → upload → marker
 *                        → ArchiveReady + ProcessingCompleted
 * </pre>
 *
 * <p>Semântica de saída:
 * <ul>
 *   <li><b>Sucesso</b>: publica Started/ArchiveReady/Completed e retorna (consumer faz ack).</li>
 *   <li><b>Job já concluído</b> (marker) ou <b>cancelado antes de iniciar</b>: retorna sem
 *       reprocessar (ack), sem republicar eventos.</li>
 *   <li><b>Cancelamento durante a execução</b>: aborta, limpa artefatos, <b>não</b> publica
 *       ProcessingCompleted; retorna (ack).</li>
 *   <li><b>Falha</b> ({@code ProcessingException}): propaga para o consumer decidir retry/DLQ e
 *       publicar {@code ProcessingFailed} (passo 7).</li>
 * </ul>
 * O diretório temporário é sempre limpo em {@code finally}.
 */
@Service
public class ProcessVideoUseCaseImpl implements ProcessVideoUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessVideoUseCaseImpl.class);

    private final VideoStorage videoStorage;
    private final ArchiveStorage archiveStorage;
    private final FrameExtractor frameExtractor;
    private final FrameArchiver frameArchiver;
    private final EventPublisher publisher;
    private final CancellationRegistry cancellationRegistry;
    private final int defaultFps;

    @Autowired
    public ProcessVideoUseCaseImpl(
            VideoStorage videoStorage,
            ArchiveStorage archiveStorage,
            FrameExtractor frameExtractor,
            FrameArchiver frameArchiver,
            EventPublisher publisher,
            CancellationRegistry cancellationRegistry,
            WorkersProperties props) {
        this(
                videoStorage,
                archiveStorage,
                frameExtractor,
                frameArchiver,
                publisher,
                cancellationRegistry,
                props.ffmpeg().defaultFps());
    }

    ProcessVideoUseCaseImpl(
            VideoStorage videoStorage,
            ArchiveStorage archiveStorage,
            FrameExtractor frameExtractor,
            FrameArchiver frameArchiver,
            EventPublisher publisher,
            CancellationRegistry cancellationRegistry,
            int defaultFps) {
        this.videoStorage = videoStorage;
        this.archiveStorage = archiveStorage;
        this.frameExtractor = frameExtractor;
        this.frameArchiver = frameArchiver;
        this.publisher = publisher;
        this.cancellationRegistry = cancellationRegistry;
        this.defaultFps = defaultFps;
    }

    @Override
    public void handle(ProcessingRequested request, String correlationId) {
        ProcessingParameters params = ProcessingParameters.resolve(request.parameters(), defaultFps);
        Job job = new Job(request.jobId(), request.ownerId(), request.videoStorageKey(), params, correlationId);

        // Idempotência: job já concluído → ack sem reprocessar (CA-W03)
        if (archiveStorage.exists(job.doneMarkerKey())) {
            log.info("Job {} já concluído (marker presente); ack sem reprocessar", job.jobId());
            return;
        }

        // Cancelamento antes de iniciar
        if (cancellationRegistry.isCancelled(job.jobId())) {
            log.info("Job {} cancelado antes de iniciar", job.jobId());
            cancellationRegistry.clear(job.jobId());
            return;
        }

        // CA-W01: usuário vê PROCESSING antes da extração
        publisher.publish(new ProcessingStarted(job.jobId()), correlationId);

        Path workDir = null;
        Path framesDir = null;
        try {
            workDir = Files.createTempDirectory("fiapx-job-" + job.jobId() + "-");
            Path video = videoStorage.download(job.videoStorageKey(), workDir);

            FrameExtractionResult extraction =
                    frameExtractor.extract(video, params, () -> cancellationRegistry.isCancelled(job.jobId()));
            framesDir = extraction.framesDirectory();

            // Ponto seguro antes de subir o resultado
            if (cancellationRegistry.isCancelled(job.jobId())) {
                throw new CancelledException(job.jobId());
            }

            Path zip = workDir.resolve(job.jobId() + ".zip");
            frameArchiver.archive(framesDir, zip);
            long sizeBytes = archiveStorage.upload(zip, job.archiveStorageKey());
            archiveStorage.writeMarker(job.doneMarkerKey());

            publisher.publish(new ArchiveReady(job.jobId(), job.archiveStorageKey(), sizeBytes), correlationId);
            publisher.publish(new ProcessingCompleted(job.jobId(), extraction.frameCount()), correlationId);
            log.info("Job {} concluído: {} frames, {} bytes", job.jobId(), extraction.frameCount(), sizeBytes);

        } catch (CancelledException e) {
            log.info("Job {} cancelado durante o processamento; removendo artefatos parciais", job.jobId());
            safeDeleteArchive(job.archiveStorageKey());
            // CA-W06: não publica ProcessingCompleted
        } catch (IOException e) {
            throw ProcessingException.transientFailure("TEMP_DIR", "Falha ao preparar o processamento do vídeo.", e);
        } finally {
            cancellationRegistry.clear(job.jobId());
            deleteDirQuietly(workDir);
            deleteDirQuietly(framesDir);
        }
    }

    private void safeDeleteArchive(String key) {
        try {
            archiveStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Falha ao remover artefato parcial {}: {}", key, e.getMessage());
        }
    }

    private void deleteDirQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException e) {
            log.warn("Falha ao limpar diretório temporário {}: {}", dir, e.getMessage());
        }
    }
}
