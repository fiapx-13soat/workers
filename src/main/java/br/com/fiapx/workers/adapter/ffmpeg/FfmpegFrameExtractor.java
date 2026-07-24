package br.com.fiapx.workers.adapter.ffmpeg;

import br.com.fiapx.workers.config.WorkersProperties;
import br.com.fiapx.workers.domain.model.CancelledException;
import br.com.fiapx.workers.domain.model.FrameExtractionResult;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.model.ProcessingParameters;
import br.com.fiapx.workers.domain.port.CancellationCheck;
import br.com.fiapx.workers.domain.port.FrameExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Extrai frames via ffmpeg ({@code ProcessBuilder}), portando o comando do projeto base:
 * {@code ffmpeg -i <video> -vf fps=<n> -y frame_%04d.png}.
 *
 * <p>Semântica de erros:
 * <ul>
 *   <li>Falha ao iniciar o processo → transitória (pode ser recurso momentâneo).</li>
 *   <li>Exit code ≠ 0 ou nenhum frame gerado → determinística (vídeo inválido/corrompido).</li>
 *   <li>Cancelamento sinalizado durante a execução → {@link CancelledException} (não é falha).</li>
 * </ul>
 *
 * <p>No sucesso, o {@code framesDirectory} retornado <b>não</b> é limpo aqui — o use case zipa
 * e depois remove o diretório temporário.
 */
@Component
public class FfmpegFrameExtractor implements FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegFrameExtractor.class);
    private static final long POLL_INTERVAL_MS = 300;

    private final String ffmpegBinary;

    @Autowired
    public FfmpegFrameExtractor(WorkersProperties props) {
        this(props.ffmpeg().binary());
    }

    // público: usado por testes de outros pacotes (ex.: ProcessVideoUseCaseImplTest)
    public FfmpegFrameExtractor(String ffmpegBinary) {
        this.ffmpegBinary = ffmpegBinary;
    }

    @Override
    public FrameExtractionResult extract(
            Path videoFile, ProcessingParameters parameters, CancellationCheck cancellationCheck) {
        // Ponto seguro: não gasta ffmpeg se o job já foi cancelado.
        if (cancellationCheck.isCancelled()) {
            throw new CancelledException();
        }
        Path framesDir = createFramesDir();
        Path logFile = createLogFile();
        Process process = start(videoFile, parameters, framesDir, logFile);

        try {
            waitForCompletion(process, cancellationCheck, framesDir, logFile);
            int exit = process.exitValue();
            if (exit != 0) {
                String tail = tailOf(logFile);
                cleanup(framesDir);
                throw ProcessingException.deterministicFailure(
                        "FFMPEG_EXIT_" + exit,
                        "Não foi possível processar o vídeo (formato inválido ou arquivo corrompido).",
                        new IllegalStateException("ffmpeg exit " + exit + ": " + tail));
            }

            List<Path> frames = listFrames(framesDir);
            if (frames.isEmpty()) {
                cleanup(framesDir);
                throw ProcessingException.deterministicFailure("NO_FRAMES", "O vídeo não gerou nenhum quadro.", null);
            }

            log.info("Extraídos {} frames (fps={})", frames.size(), parameters.fps());
            return new FrameExtractionResult(frames.size(), framesDir);

        } finally {
            deleteQuietly(logFile);
        }
    }

    private Process start(Path videoFile, ProcessingParameters parameters, Path framesDir, Path logFile) {
        String pattern = framesDir.resolve("frame_%04d.png").toString();
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegBinary, "-i", videoFile.toString(), "-vf", "fps=" + parameters.fps(), "-y", pattern);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile()); // evita deadlock de buffer e guarda diagnóstico
        try {
            return pb.start();
        } catch (IOException e) {
            cleanup(framesDir);
            deleteQuietly(logFile);
            throw ProcessingException.transientFailure(
                    "FFMPEG_START", "Não foi possível iniciar o processamento do vídeo.", e);
        }
    }

    private void waitForCompletion(Process process, CancellationCheck cancellationCheck, Path framesDir, Path logFile) {
        try {
            while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (cancellationCheck.isCancelled()) {
                    process.destroyForcibly();
                    cleanup(framesDir);
                    deleteQuietly(logFile);
                    throw new CancelledException();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            cleanup(framesDir);
            deleteQuietly(logFile);
            throw ProcessingException.transientFailure("INTERRUPTED", "Processamento interrompido.", e);
        }
    }

    private List<Path> listFrames(Path framesDir) {
        try (Stream<Path> stream = Files.list(framesDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            cleanup(framesDir);
            throw ProcessingException.transientFailure("LIST_FRAMES", "Falha ao ler os quadros extraídos.", e);
        }
    }

    private Path createFramesDir() {
        try {
            return Files.createTempDirectory("fiapx-frames-");
        } catch (IOException e) {
            throw ProcessingException.transientFailure("TEMP_DIR", "Falha ao preparar o processamento.", e);
        }
    }

    private Path createLogFile() {
        try {
            return Files.createTempFile("fiapx-ffmpeg-", ".log");
        } catch (IOException e) {
            throw ProcessingException.transientFailure("TEMP_LOG", "Falha ao preparar o processamento.", e);
        }
    }

    private String tailOf(Path logFile) {
        try {
            String content = Files.readString(logFile);
            int max = 1000;
            return content.length() <= max ? content : content.substring(content.length() - max);
        } catch (IOException e) {
            return "(log indisponível)";
        }
    }

    private void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Falha ao limpar diretório temporário {}: {}", dir, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
