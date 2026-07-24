package br.com.fiapx.workers.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProcessingParametersTest {

    @Test
    void resolveAplicaDefaultQuandoParametrosAusentes() {
        ProcessingParameters resolved = ProcessingParameters.resolve(null, 3);
        assertEquals(3, resolved.fps());
    }

    @Test
    void resolveMantemFpsInformado() {
        ProcessingParameters resolved = ProcessingParameters.resolve(new ProcessingParameters(5), 1);
        assertEquals(5, resolved.fps());
    }

    @Test
    void rejeitaFpsNaoPositivo() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessingParameters(0));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingParameters(-1));
    }
}
