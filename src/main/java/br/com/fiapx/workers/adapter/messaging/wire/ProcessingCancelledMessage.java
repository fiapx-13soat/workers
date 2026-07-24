package br.com.fiapx.workers.adapter.messaging.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Representação de fio de {@code ProcessingCancelled}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessingCancelledMessage(String jobId) {}
