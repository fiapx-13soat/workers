package br.com.fiapx.workers.adapter.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.fiapx.workers.adapter.messaging.wire.ProcessingRequestedMessage;
import br.com.fiapx.workers.adapter.observability.WorkerMetrics;
import br.com.fiapx.workers.application.ProcessVideoUseCase;
import br.com.fiapx.workers.domain.model.ProcessingException;
import com.fasterxml.jackson.databind.node.NullNode;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Orquestração do consumo: toda mensagem termina em ack; retry/DLQ é por republicação (failure
 * handler). Cobre sucesso, mensagem malformada e falha de processamento (CA-W05/W08).
 */
class JobConsumerTest {

    private ProcessVideoUseCase useCase;
    private EventEnvelopeCodec codec;
    private JobFailureHandler failureHandler;
    private WorkerMetrics metrics;
    private JobConsumer consumer;
    private Channel channel;
    private Message message;

    @BeforeEach
    void setup() {
        useCase = mock(ProcessVideoUseCase.class);
        codec = mock(EventEnvelopeCodec.class);
        failureHandler = mock(JobFailureHandler.class);
        metrics = mock(WorkerMetrics.class);
        channel = mock(Channel.class);
        consumer = new JobConsumer(useCase, codec, failureHandler, metrics);

        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(7L);
        message = new Message("{}".getBytes(), props);
    }

    private void stubValidDecode() {
        when(codec.decode(any()))
                .thenReturn(new EventEnvelopeCodec.Decoded("ProcessingRequested", "corr", NullNode.instance));
        when(codec.toPayload(any(), eq(ProcessingRequestedMessage.class)))
                .thenReturn(new ProcessingRequestedMessage(
                        "job-1", "videos/job-1.mp4", new ProcessingRequestedMessage.Parameters(1), "owner"));
    }

    @Test
    void sucessoAckaAMensagem() throws IOException {
        stubValidDecode();

        consumer.onJob(message, channel);

        verify(useCase).handle(any(), eq("corr"));
        verify(metrics).completed(any());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void malformadoVaiParaDlqSemRetryEAcka() throws IOException {
        when(codec.decode(any())).thenThrow(new MessageDecodingException("envelope inválido"));

        consumer.onJob(message, channel);

        verify(metrics).malformed();
        verify(failureHandler).onMalformedMessage(eq(message), any(), any());
        verify(useCase, never()).handle(any(), anyString());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void falhaDeProcessamentoDelegaAoFailureHandlerEAcka() throws IOException {
        stubValidDecode();
        doThrow(ProcessingException.deterministicFailure("VIDEO_CORRUPT", "Vídeo corrompido.", new RuntimeException()))
                .when(useCase)
                .handle(any(), anyString());

        consumer.onJob(message, channel);

        verify(metrics).failed(any());
        verify(failureHandler)
                .onProcessingFailure(
                        eq(message), anyInt(), eq("job-1"), eq("corr"), eq("VIDEO_CORRUPT"), any(), eq(false));
        verify(channel).basicAck(7L, false);
    }
}
