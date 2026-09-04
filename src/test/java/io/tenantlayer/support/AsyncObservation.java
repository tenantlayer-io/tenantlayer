package io.tenantlayer.support;

/** What a worker thread could actually see, captured on that thread. */
public record AsyncObservation(String tenant, String threadName, long visibleRows) {
}
