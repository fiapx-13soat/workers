package br.com.fiapx.workers.adapter.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.fiapx.workers.domain.model.ProcessingException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Integração dos adapters S3 contra LocalStack (emula o S3 do Floci). Requer Docker.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageIntegrationTest {

    private static final String VIDEOS = "fiapx-videos";
    private static final String ARCHIVES = "fiapx-archives";

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private S3Client s3;
    private S3VideoStorage videoStorage;
    private S3ArchiveStorage archiveStorage;

    @BeforeAll
    void setup() {
        s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        s3.createBucket(CreateBucketRequest.builder().bucket(VIDEOS).build());
        s3.createBucket(CreateBucketRequest.builder().bucket(ARCHIVES).build());

        videoStorage = new S3VideoStorage(s3, VIDEOS);
        archiveStorage = new S3ArchiveStorage(s3, ARCHIVES);
    }

    @AfterAll
    void teardown() {
        if (s3 != null) s3.close();
    }

    @Test
    void baixaVideoDoBucket(@TempDir Path tempDir) throws Exception {
        byte[] content = "conteudo-do-video".getBytes();
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(VIDEOS)
                        .key("videos/job-1.mp4")
                        .build(),
                RequestBody.fromBytes(content));

        Path downloaded = videoStorage.download("videos/job-1.mp4", tempDir);

        assertTrue(Files.exists(downloaded));
        assertEquals("job-1.mp4", downloaded.getFileName().toString());
        assertArrayEquals(content, Files.readAllBytes(downloaded));
    }

    @Test
    void videoInexistenteGeraFalhaDeterministica(@TempDir Path tempDir) {
        ProcessingException ex =
                assertThrows(ProcessingException.class, () -> videoStorage.download("videos/nao-existe.mp4", tempDir));
        assertFalse(ex.isTransient());
        assertEquals("VIDEO_NOT_FOUND", ex.errorCode());
    }

    @Test
    void uploadRetornaTamanhoEExists(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("out.zip");
        Files.write(zip, new byte[] {1, 2, 3, 4, 5});

        long size = archiveStorage.upload(zip, "archives/job-2.zip");

        assertEquals(5, size);
        assertTrue(archiveStorage.exists("archives/job-2.zip"));
        assertFalse(archiveStorage.exists("archives/inexistente.zip"));
    }

    @Test
    void markerDeIdempotencia() {
        assertFalse(archiveStorage.exists("markers/job-3.done"));
        archiveStorage.writeMarker("markers/job-3.done");
        assertTrue(archiveStorage.exists("markers/job-3.done"));
    }

    @Test
    void deleteRemoveObjeto(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("del.zip");
        Files.write(zip, new byte[] {9, 9});
        archiveStorage.upload(zip, "archives/job-4.zip");
        assertTrue(archiveStorage.exists("archives/job-4.zip"));

        archiveStorage.delete("archives/job-4.zip");

        assertFalse(archiveStorage.exists("archives/job-4.zip"));
    }
}
