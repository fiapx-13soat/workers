package br.com.fiapx.workers.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobTest {

    private Job job(String jobId, String videoKey) {
        return new Job(jobId, "owner-1", videoKey, new ProcessingParameters(1), "corr-1");
    }

    @Test
    void derivaChavesDeArchiveEMarker() {
        Job job = job("abc-123", "videos/abc-123.mp4");
        assertEquals("archives/abc-123.zip", job.archiveStorageKey());
        assertEquals("markers/abc-123.done", job.doneMarkerKey());
    }

    @Test
    void exigeJobIdEVideoKey() {
        assertThrows(IllegalArgumentException.class, () -> job(" ", "videos/x.mp4"));
        assertThrows(IllegalArgumentException.class, () -> job("abc", " "));
    }
}
