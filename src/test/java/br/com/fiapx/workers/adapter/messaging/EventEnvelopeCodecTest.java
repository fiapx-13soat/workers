package br.com.fiapx.workers.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.fiapx.workers.adapter.messaging.wire.ProcessingRequestedMessage;
import br.com.fiapx.workers.domain.event.ProcessingFailed;
import br.com.fiapx.workers.domain.event.ProcessingStarted;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Contrato do envelope: encode/decode, campo {@code transient} e rejeição de malformado/versão. */
class EventEnvelopeCodecTest {

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void encodeEnvolveNoEnvelopePadraoEPropagaCorrelationId() throws Exception {
        byte[] body = codec.encode(new ProcessingStarted("job-1"), "corr-9");

        JsonNode env = mapper.readTree(body);
        assertEquals("ProcessingStarted", env.get("eventType").asText());
        assertEquals(1, env.get("schemaVersion").asInt());
        assertEquals("corr-9", env.get("correlationId").asText());
        assertTrue(env.hasNonNull("eventId"));
        assertTrue(env.hasNonNull("occurredAt"));
        assertEquals("job-1", env.get("payload").get("jobId").asText());

        EventEnvelopeCodec.Decoded decoded = codec.decode(body);
        assertEquals("ProcessingStarted", decoded.eventType());
        assertEquals("corr-9", decoded.correlationId());
    }

    @Test
    void processingFailedSerializaCampoTransientNaoTransientError() throws Exception {
        byte[] body =
                codec.encode(new ProcessingFailed("job-2", "VIDEO_NOT_FOUND", "Vídeo não encontrado.", false), "c");

        JsonNode payload = mapper.readTree(body).get("payload");
        assertTrue(payload.has("transient"), "payload deve expor 'transient'");
        assertTrue(!payload.has("transientError"), "não deve vazar 'transientError'");
        assertEquals(false, payload.get("transient").asBoolean());
        assertEquals("VIDEO_NOT_FOUND", payload.get("errorCode").asText());
    }

    @Test
    void toPayloadConverteParaOTipoDeFio() {
        String json =
                """
                {"eventType":"ProcessingRequested","schemaVersion":1,"eventId":"e","occurredAt":"2026-01-01T00:00:00Z",
                 "correlationId":"c","payload":{"jobId":"j","videoStorageKey":"videos/j.mp4","ownerId":"o","parameters":{"fps":2}}}
                """;
        EventEnvelopeCodec.Decoded decoded = codec.decode(json.getBytes(StandardCharsets.UTF_8));
        ProcessingRequestedMessage wire = codec.toPayload(decoded.payload(), ProcessingRequestedMessage.class);
        assertEquals("j", wire.jobId());
        assertEquals("videos/j.mp4", wire.videoStorageKey());
        assertEquals(2, wire.parameters().fps());
    }

    @Test
    void corpoMalformadoLancaMessageDecodingException() {
        assertThrows(
                MessageDecodingException.class, () -> codec.decode("{{ não é json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void schemaVersionIncompativelVaiParaDecodingException() {
        String v2 =
                """
                {"eventType":"ProcessingRequested","schemaVersion":2,"eventId":"e","occurredAt":"2026-01-01T00:00:00Z",
                 "correlationId":"c","payload":{"jobId":"j"}}
                """;
        MessageDecodingException ex =
                assertThrows(MessageDecodingException.class, () -> codec.decode(v2.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("schemaVersion"));
    }
}
