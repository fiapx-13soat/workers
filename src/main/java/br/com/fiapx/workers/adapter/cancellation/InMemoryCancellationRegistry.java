package br.com.fiapx.workers.adapter.cancellation;

import br.com.fiapx.workers.domain.port.CancellationRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Registro em memória de jobs cancelados. Cada réplica mantém o seu — o cancelamento é
 * transmitido por broadcast (fila exclusiva por instância ligada a {@code job.cancelled}),
 * então todas as réplicas veem o sinal e a que estiver processando o job aborta.
 */
@Component
public class InMemoryCancellationRegistry implements CancellationRegistry {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    @Override
    public void markCancelled(String jobId) {
        cancelled.add(jobId);
    }

    @Override
    public boolean isCancelled(String jobId) {
        return cancelled.contains(jobId);
    }

    @Override
    public void clear(String jobId) {
        cancelled.remove(jobId);
    }
}
