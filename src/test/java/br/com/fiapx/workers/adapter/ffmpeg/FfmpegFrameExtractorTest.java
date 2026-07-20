package br.com.fiapx.workers.adapter.ffmpeg;

import br.com.fiapx.workers.adapter.archive.ZipFrameArchiver;
import br.com.fiapx.workers.domain.model.CancelledException;
import br.com.fiapx.workers.domain.model.FrameExtractionResult;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.model.ProcessingParameters;
import br.com.fiapx.workers.domain.port.CancellationCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integração com o ffmpeg real (requer ffmpeg no PATH). Usa o fixture
 * {@code src/test/resources/fixtures/sample.mp4} (3s, 32x32, gerado com testsrc).
 */
class FfmpegFrameExtractorTest {

    private final FfmpegFrameExtractor extractor = new FfmpegFrameExtractor("ffmpeg");
    private static final CancellationCheck NEVER_CANCELLED = () -> false;

    private Path fixture() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixtures/sample.mp4").toURI());
    }

    @Test
    void extraiFramesDeVideoValidoEZipa(@TempDir Path tempDir) throws Exception {
        FrameExtractionResult result = extractor.extract(
                fixture(), new ProcessingParameters(1), NEVER_CANCELLED);

        // 3s a 1 fps → 3 frames
        assertEquals(3, result.frameCount());
        assertTrue(Files.isDirectory(result.framesDirectory()));

        Path zip = tempDir.resolve("frames.zip");
        int entries = new ZipFrameArchiver().archive(result.framesDirectory(), zip);

        assertEquals(3, entries);
        assertTrue(Files.size(zip) > 0);
        assertEquals(3, countZipEntries(zip));

        cleanup(result.framesDirectory());
    }

    @Test
    void respeitaFpsDoParametro(@TempDir Path tempDir) throws Exception {
        FrameExtractionResult result = extractor.extract(
                fixture(), new ProcessingParameters(2), NEVER_CANCELLED);
        // 3s a 2 fps → ~6 frames
        assertTrue(result.frameCount() >= 5, "esperava ~6 frames, veio " + result.frameCount());
        cleanup(result.framesDirectory());
    }

    @Test
    void videoCorrompidoGeraFalhaDeterministica(@TempDir Path tempDir) throws Exception {
        Path corrupt = tempDir.resolve("corrupt.mp4");
        Files.writeString(corrupt, "isto nao e um video valido");

        ProcessingException ex = assertThrows(ProcessingException.class,
                () -> extractor.extract(corrupt, new ProcessingParameters(1), NEVER_CANCELLED));

        assertFalse(ex.isTransient(), "vídeo corrompido é falha determinística");
    }

    @Test
    void jobJaCanceladoAbortaAntesDeIniciar() throws Exception {
        CancellationCheck alwaysCancelled = () -> true;
        assertThrows(CancelledException.class,
                () -> extractor.extract(fixture(), new ProcessingParameters(1), alwaysCancelled));
    }

    private long countZipEntries(Path zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            long count = 0;
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                e.nextElement();
                count++;
            }
            return count;
        }
    }

    private void cleanup(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
