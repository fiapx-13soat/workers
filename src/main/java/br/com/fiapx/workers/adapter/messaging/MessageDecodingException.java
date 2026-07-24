package br.com.fiapx.workers.adapter.messaging;

/**
 * Mensagem malformada (envelope/payload inválido). Determinística: vai direto para a DLQ,
 * sem requeue — retentar não corrige um JSON quebrado.
 */
public class MessageDecodingException extends RuntimeException {

    public MessageDecodingException(String message) {
        super(message);
    }

    public MessageDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
