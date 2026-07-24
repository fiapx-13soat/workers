package br.com.fiapx.workers.adapter.messaging;

import br.com.fiapx.workers.domain.event.EventEnvelope;
import br.com.fiapx.workers.domain.event.EventTypes;
import br.com.fiapx.workers.domain.event.OutboundEvent;
import br.com.fiapx.workers.domain.event.ProcessingFailed;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * (De)serialização do envelope padrão. Controla a forma exata do JSON do contrato:
 * datas ISO-8601 e o campo {@code transient} (mapeado a partir de {@code transientError}
 * via mixin, mantendo o domínio livre de anotações Jackson).
 */
@Component
public class EventEnvelopeCodec {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addMixIn(ProcessingFailed.class, ProcessingFailedMixin.class)
            .build();

    /** Envolve o evento no envelope padrão e serializa para bytes JSON. */
    public byte[] encode(OutboundEvent event, String correlationId) {
        EventEnvelope<OutboundEvent> envelope = new EventEnvelope<>(
                event.eventType(),
                EventTypes.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                Instant.now(),
                correlationId,
                event);
        try {
            return mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar evento " + event.eventType(), e);
        }
    }

    /** Lê o envelope como árvore, expondo os campos comuns e o payload cru. */
    public Decoded decode(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new MessageDecodingException("Envelope inválido", e);
        }
        // schemaVersion divergente = contrato incompatível (bump exige PR conjunto): trata como
        // malformado → o JobConsumer manda para a DLQ sem retry, em vez de aceitar em silêncio.
        int version = root.path("schemaVersion").asInt(-1);
        if (version != EventTypes.SCHEMA_VERSION) {
            throw new MessageDecodingException(
                    "schemaVersion incompatível: esperado " + EventTypes.SCHEMA_VERSION + ", recebido " + version);
        }
        return new Decoded(
                root.path("eventType").asText(null),
                root.hasNonNull("correlationId") ? root.get("correlationId").asText() : null,
                root.path("payload"));
    }

    /** Converte o payload cru para o tipo de fio esperado. */
    public <T> T toPayload(JsonNode payload, Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (Exception e) {
            throw new MessageDecodingException("Payload inválido para " + type.getSimpleName(), e);
        }
    }

    /** Campos comuns do envelope + payload cru. */
    public record Decoded(String eventType, String correlationId, JsonNode payload) {}

    /** Mapeia {@code transientError} → {@code "transient"} (palavra reservada em Java) na saída. */
    abstract static class ProcessingFailedMixin {
        @JsonProperty("transient")
        abstract boolean transientError();
    }
}
