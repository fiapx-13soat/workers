package br.com.fiapx.workers.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Bean do {@link S3Client}. A única diferença entre local e AWS é o endpoint:
 * <ul>
 *   <li>{@code AWS_ENDPOINT_URL} vazio → AWS real, credenciais da task role (default provider chain).</li>
 *   <li>{@code AWS_ENDPOINT_URL} preenchido → Floci/LocalStack: path-style + credenciais dummy.</li>
 * </ul>
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(WorkersProperties props) {
        String endpointUrl = props.aws().endpointUrl();
        var builder = S3Client.builder().region(Region.of(props.aws().region()));

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl))
                    .forcePathStyle(true) // buckets como path (necessário no Floci/LocalStack)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
