package br.com.fiapx.workers.domain.model;

/**
 * Falha de processamento com classificação para a política de retry/DLQ:
 * <ul>
 *   <li>{@code transientError = true}  → falha transitória (timeout S3, rede) → retry com backoff.</li>
 *   <li>{@code transientError = false} → falha determinística (vídeo corrompido) → DLQ + {@code ProcessingFailed}.</li>
 * </ul>
 * {@code friendlyMessage} é seguro para exibir ao usuário (vai no {@code ProcessingFailed}).
 */
public class ProcessingException extends RuntimeException {

    private final String errorCode;
    private final boolean transientError;
    private final String friendlyMessage;

    public ProcessingException(String errorCode, String friendlyMessage, boolean transientError, Throwable cause) {
        super(friendlyMessage, cause);
        this.errorCode = errorCode;
        this.friendlyMessage = friendlyMessage;
        this.transientError = transientError;
    }

    public static ProcessingException transientFailure(String errorCode, String friendlyMessage, Throwable cause) {
        return new ProcessingException(errorCode, friendlyMessage, true, cause);
    }

    public static ProcessingException deterministicFailure(String errorCode, String friendlyMessage, Throwable cause) {
        return new ProcessingException(errorCode, friendlyMessage, false, cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean isTransient() {
        return transientError;
    }

    public String friendlyMessage() {
        return friendlyMessage;
    }
}
