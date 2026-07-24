package br.com.fiapx.workers.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração do serviço, tipada e validada num só lugar (prefixo {@code workers}).
 *
 * <p>Substitui os {@code @Value("${...}")} antes espalhados pelos adapters: as mesmas chaves e
 * defaults do {@code application.yml}, agora com validação no boot — env obrigatória ausente
 * derruba a aplicação com mensagem clara, em vez de conectar num default silencioso.
 */
@ConfigurationProperties(prefix = "workers")
@Validated
public record WorkersProperties(
        @NotNull @Valid Rabbit rabbit,
        @NotNull @Valid Aws aws,
        @NotNull @Valid Ffmpeg ffmpeg,
        @NotNull @Valid Retry retry,
        @DefaultValue("120s") Duration shutdownTimeout) {

    public record Rabbit(
            @NotBlank String exchange,
            @NotBlank String queueJobs,
            @NotBlank String queueRetry,
            @NotBlank String queueDlq) {}

    public record Aws(
            @DefaultValue("") String endpointUrl, // vazio = AWS real; preenchido = Floci/LocalStack
            @NotBlank String region,
            @NotNull @Valid S3 s3) {

        public record S3(@NotBlank String bucketVideos, @NotBlank String bucketArchives) {}
    }

    public record Ffmpeg(
            @DefaultValue("ffmpeg") @NotBlank String binary, @DefaultValue("1") @Positive int defaultFps) {}

    public record Retry(@NotEmpty long[] delaysMs) {}
}
