package br.com.fiapx.workers.domain.model;

/**
 * Parâmetros de extração de frames. Hoje só {@code fps}; extensível no futuro.
 */
public record ProcessingParameters(int fps) {

    public static final int DEFAULT_FPS = 1;

    public ProcessingParameters {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps deve ser maior que zero, recebido: " + fps);
        }
    }

    /**
     * Resolve os parâmetros efetivos aplicando o default quando o evento não informa fps.
     *
     * @param requested parâmetros do payload (pode ser {@code null})
     * @param defaultFps fps default do serviço ({@code DEFAULT_FPS} do config)
     */
    public static ProcessingParameters resolve(ProcessingParameters requested, int defaultFps) {
        if (requested != null) {
            return requested;
        }
        return new ProcessingParameters(defaultFps);
    }
}
