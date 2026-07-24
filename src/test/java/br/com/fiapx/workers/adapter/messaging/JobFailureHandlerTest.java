package br.com.fiapx.workers.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import br.com.fiapx.workers.domain.event.OutboundEvent;
import br.com.fiapx.workers.domain.event.ProcessingFailed;
import br.com.fiapx.workers.domain.port.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Política de falha do consumo (CA-W04/W05): retry com backoff vs DLQ + ProcessingFailed. */
class JobFailureHandlerTest {

    private RabbitTemplate rabbit;
    private EventPublisher publisher;
    private JobFailureHandler handler;

    private final Message msg = MessageBuilder.withBody("{}".getBytes()).build();

    @BeforeEach
    void setup() {
        rabbit = mock(RabbitTemplate.class);
        publisher = mock(EventPublisher.class);
        // construtor de teste (primitivos): 2 tentativas antes da DLQ
        handler = new JobFailureHandler(rabbit, publisher, new long[] {1000, 5000}, "q.retry", "q.dlq");
    }

    @Test
    void transitorioComTentativasRestantesRepublicaNaRetryComBackoff() {
        handler.onProcessingFailure(msg, 0, "job-1", "corr", "S3_DOWNLOAD", "Falha ao obter o vídeo.", true);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(""), eq("q.retry"), sent.capture());
        assertEquals("1000", sent.getValue().getMessageProperties().getExpiration()); // backoff da tentativa 0
        Integer nextAttempt = sent.getValue().getMessageProperties().getHeader(JobFailureHandler.ATTEMPT_HEADER);
        assertEquals(Integer.valueOf(1), nextAttempt); // próxima tentativa
        // retry não publica ProcessingFailed
        verify(publisher, never()).publish(any(OutboundEvent.class), any());
    }

    @Test
    void tentativasEsgotadasVaoParaDlqComProcessingFailed() {
        handler.onProcessingFailure(msg, 2, "job-1", "corr", "S3_DOWNLOAD", "Falha ao obter o vídeo.", true);

        verify(rabbit).send(eq(""), eq("q.dlq"), any(Message.class));
        ArgumentCaptor<OutboundEvent> ev = ArgumentCaptor.forClass(OutboundEvent.class);
        verify(publisher).publish(ev.capture(), eq("corr"));
        assertEquals("ProcessingFailed", ev.getValue().eventType());
        assertEquals("S3_DOWNLOAD", ((ProcessingFailed) ev.getValue()).errorCode());
    }

    @Test
    void falhaDeterministicaVaiDiretoParaDlq() {
        // transientError=false → DLQ mesmo com tentativas restantes
        handler.onProcessingFailure(msg, 0, "job-1", "corr", "VIDEO_CORRUPT", "Vídeo corrompido.", false);

        verify(rabbit).send(eq(""), eq("q.dlq"), any(Message.class));
        verify(rabbit, never()).send(eq(""), eq("q.retry"), any(Message.class));
        verify(publisher).publish(any(OutboundEvent.class), eq("corr"));
    }

    @Test
    void mensagemMalformadaVaiParaDlqComProcessingFailedInvalidMessage() {
        handler.onMalformedMessage(msg, "job-1", "corr");

        verify(rabbit).send(eq(""), eq("q.dlq"), any(Message.class));
        ArgumentCaptor<OutboundEvent> ev = ArgumentCaptor.forClass(OutboundEvent.class);
        verify(publisher).publish(ev.capture(), eq("corr"));
        assertEquals("INVALID_MESSAGE", ((ProcessingFailed) ev.getValue()).errorCode());
    }
}
