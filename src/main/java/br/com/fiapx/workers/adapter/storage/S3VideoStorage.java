package br.com.fiapx.workers.adapter.storage;

import br.com.fiapx.workers.config.WorkersProperties;
import br.com.fiapx.workers.domain.model.ProcessingException;
import br.com.fiapx.workers.domain.port.VideoStorage;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Leitura do bucket de vídeos (origem). Baixa o objeto para um arquivo local.
 */
@Component
public class S3VideoStorage implements VideoStorage {

    private final S3Client s3;
    private final String bucket;

    @Autowired
    public S3VideoStorage(S3Client s3, WorkersProperties props) {
        this(s3, props.aws().s3().bucketVideos());
    }

    S3VideoStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Path download(String storageKey, Path targetDirectory) {
        Path target = targetDirectory.resolve(fileNameOf(storageKey));
        try {
            s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build(), target);
            return target;
        } catch (NoSuchKeyException e) {
            // vídeo não existe → não adianta retry
            throw ProcessingException.deterministicFailure("VIDEO_NOT_FOUND", "O vídeo enviado não foi encontrado.", e);
        } catch (S3Exception | SdkClientException e) {
            // rede/servidor → transitório
            throw ProcessingException.transientFailure("S3_DOWNLOAD", "Falha ao obter o vídeo para processamento.", e);
        }
    }

    private String fileNameOf(String storageKey) {
        int slash = storageKey.lastIndexOf('/');
        String name = slash >= 0 ? storageKey.substring(slash + 1) : storageKey;
        return name.isBlank() ? "video" : name;
    }
}
