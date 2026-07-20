package br.com.fiapx.workers.adapter.archive;

import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.port.FrameArchiver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zipa os PNGs de um diretório em ordem determinística (nome), usando Deflate.
 * Porte direto do {@code createZipFile}/{@code addFileToZip} do {@code projeto-fiapx}.
 */
@Component
public class ZipFrameArchiver implements FrameArchiver {

    @Override
    public int archive(Path framesDirectory, Path zipTarget) {
        try (OutputStream out = Files.newOutputStream(zipTarget);
             ZipOutputStream zip = new ZipOutputStream(out)) {

            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);

            List<Path> files;
            try (Stream<Path> stream = Files.list(framesDirectory)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            }

            for (Path file : files) {
                ZipEntry entry = new ZipEntry(file.getFileName().toString());
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
            return files.size();

        } catch (IOException e) {
            throw ProcessingException.transientFailure(
                    "ZIP_FAILED", "Falha ao gerar o arquivo de resultado.", e);
        }
    }
}
