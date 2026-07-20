package br.com.fiapx.workers.domain.port;

/**
 * Sinal de cancelamento consultável em pontos seguros durante a extração.
 * Implementado sobre o {@link CancellationRegistry} para um job específico.
 */
@FunctionalInterface
public interface CancellationCheck {

    boolean isCancelled();
}
