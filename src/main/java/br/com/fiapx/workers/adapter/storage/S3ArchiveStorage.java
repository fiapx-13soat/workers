package br.com.fiapx.workers.adapter.storage;

import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.port.ArchiveStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Escrita/leitura do bucket de archives (destino): ZIP de resultado e markers de idempotência.
 */
@Component
public class S3ArchiveStorage implements ArchiveStorage {

    private final S3Client s3;
    private final String bucket;

    public S3ArchiveStorage(S3Client s3, @Value("${workers.aws.s3.bucket-archives}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public long upload(Path zipFile, String storageKey) {
        try {
            long size = Files.size(zipFile);
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(storageKey)
                            .contentType("application/zip").build(),
                    RequestBody.fromFile(zipFile));
            return size;
        } catch (IOException | S3Exception e) {
            throw ProcessingException.transientFailure(
                    "S3_UPLOAD", "Falha ao salvar o arquivo de resultado.", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw ProcessingException.transientFailure(
                    "S3_HEAD", "Falha ao verificar o resultado no armazenamento.", e);
        }
    }

    @Override
    public void writeMarker(String markerKey) {
        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(markerKey).build(),
                    RequestBody.empty());
        } catch (S3Exception e) {
            throw ProcessingException.transientFailure(
                    "S3_MARKER", "Falha ao registrar conclusão do processamento.", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (S3Exception e) {
            throw ProcessingException.transientFailure(
                    "S3_DELETE", "Falha ao remover artefato do armazenamento.", e);
        }
    }
}
