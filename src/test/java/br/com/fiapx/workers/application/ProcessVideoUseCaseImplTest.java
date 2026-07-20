package br.com.fiapx.workers.application;

import br.com.fiapx.workers.adapter.archive.ZipFrameArchiver;
import br.com.fiapx.workers.adapter.cancellation.InMemoryCancellationRegistry;
import br.com.fiapx.workers.adapter.ffmpeg.FfmpegFrameExtractor;
import br.com.fiapx.workers.domain.event.ArchiveReady;
import br.com.fiapx.workers.domain.event.OutboundEvent;
import br.com.fiapx.workers.domain.event.ProcessingCompleted;
import br.com.fiapx.workers.domain.event.ProcessingRequested;
import br.com.fiapx.workers.domain.event.ProcessingStarted;
import br.com.fiapx.workers.domain.model.ProcessingParameters;
import br.com.fiapx.workers.domain.port.ArchiveStorage;
import br.com.fiapx.workers.domain.port.CancellationRegistry;
import br.com.fiapx.workers.domain.port.EventPublisher;
import br.com.fiapx.workers.domain.port.VideoStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orquestração do use case com ffmpeg e zip <b>reais</b> e fakes em memória para S3/eventos.
 * Rápido e determinístico (sem containers). Requer ffmpeg no PATH.
 */
class ProcessVideoUseCaseImplTest {

    private FakeVideoStorage videoStorage;
    private InMemoryArchiveStorage archiveStorage;
    private RecordingPublisher publisher;
    private CancellationRegistry cancellation;
    private ProcessVideoUseCaseImpl useCase;

    @BeforeEach
    void setup() throws Exception {
        Path fixture = Path.of(getClass().getClassLoader().getResource("fixtures/sample.mp4").toURI());
        videoStorage = new FakeVideoStorage(fixture);
        archiveStorage = new InMemoryArchiveStorage();
        publisher = new RecordingPublisher();
        cancellation = new InMemoryCancellationRegistry();
        useCase = new ProcessVideoUseCaseImpl(
                videoStorage, archiveStorage,
                new FfmpegFrameExtractor("ffmpeg"), new ZipFrameArchiver(),
                publisher, cancellation, 1);
    }

    private ProcessingRequested request(String jobId) {
        return new ProcessingRequested(jobId, "videos/" + jobId + ".mp4", null, "owner-1");
    }

    @Test
    void fluxoFelizPublicaStartedArchiveReadyCompletedEGravaArchiveEMarker() {
        useCase.handle(request("job-1"), "corr-1");

        List<OutboundEvent> events = publisher.events;
        assertEquals(3, events.size(), "esperava Started, ArchiveReady, Completed");
        assertInstanceOf(ProcessingStarted.class, events.get(0));
        assertInstanceOf(ArchiveReady.class, events.get(1));
        assertInstanceOf(ProcessingCompleted.class, events.get(2));

        ArchiveReady archive = (ArchiveReady) events.get(1);
        assertEquals("archives/job-1.zip", archive.archiveStorageKey());
        assertTrue(archive.sizeBytes() > 0);

        ProcessingCompleted completed = (ProcessingCompleted) events.get(2);
        assertEquals(3, completed.frameCount()); // 3s @ 1fps

        assertTrue(archiveStorage.exists("archives/job-1.zip"), "ZIP deve estar no bucket de archives");
        assertTrue(archiveStorage.exists("markers/job-1.done"), "marker de idempotência deve existir");
    }

    @Test
    void jobJaConcluidoNaoReprocessa() {
        archiveStorage.writeMarker("markers/job-2.done"); // simula conclusão anterior
        useCase.handle(request("job-2"), "corr-2");

        assertTrue(publisher.events.isEmpty(), "não deve republicar eventos para job já concluído");
        assertFalse(archiveStorage.exists("archives/job-2.zip"), "não deve regravar o archive");
    }

    @Test
    void jobCanceladoAntesDeIniciarNaoPublicaNada() {
        cancellation.markCancelled("job-3");
        useCase.handle(request("job-3"), "corr-3");

        assertTrue(publisher.events.isEmpty());
        assertFalse(cancellation.isCancelled("job-3"), "registry deve ser limpo");
    }

    @Test
    void canceladoDuranteExtracaoPublicaStartedMasNaoCompleted() {
        // false na pré-checagem (publica Started), true na entrada do extractor (aborta)
        CountingCancellationRegistry counting = new CountingCancellationRegistry();
        var uc = new ProcessVideoUseCaseImpl(
                videoStorage, archiveStorage,
                new FfmpegFrameExtractor("ffmpeg"), new ZipFrameArchiver(),
                publisher, counting, 1);

        uc.handle(request("job-4"), "corr-4");

        assertEquals(1, publisher.events.size());
        assertInstanceOf(ProcessingStarted.class, publisher.events.get(0));
        assertFalse(archiveStorage.exists("archives/job-4.zip"), "não deve subir archive de job cancelado");
        assertFalse(archiveStorage.exists("markers/job-4.done"));
    }

    // ---- Fakes ----

    static class FakeVideoStorage implements VideoStorage {
        private final Path fixture;

        FakeVideoStorage(Path fixture) {
            this.fixture = fixture;
        }

        @Override
        public Path download(String storageKey, Path targetDirectory) {
            try {
                Path target = targetDirectory.resolve("video.mp4");
                Files.copy(fixture, target);
                return target;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class InMemoryArchiveStorage implements ArchiveStorage {
        final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public long upload(Path zipFile, String storageKey) {
            try {
                byte[] bytes = Files.readAllBytes(zipFile);
                store.put(storageKey, bytes);
                return bytes.length;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean exists(String storageKey) {
            return store.containsKey(storageKey);
        }

        @Override
        public void writeMarker(String markerKey) {
            store.put(markerKey, new byte[0]);
        }

        @Override
        public void delete(String storageKey) {
            store.remove(storageKey);
        }
    }

    static class RecordingPublisher implements EventPublisher {
        final List<OutboundEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(OutboundEvent event, String correlationId) {
            events.add(event);
        }
    }

    /** Retorna false na 1ª consulta (pré-checagem) e true nas seguintes (entrada do extractor). */
    static class CountingCancellationRegistry implements CancellationRegistry {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void markCancelled(String jobId) {
        }

        @Override
        public boolean isCancelled(String jobId) {
            return calls.getAndIncrement() > 0;
        }

        @Override
        public void clear(String jobId) {
        }
    }
}
