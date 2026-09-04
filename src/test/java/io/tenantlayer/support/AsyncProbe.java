package io.tenantlayer.support;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AsyncProbe {

    private final DocumentRepository documents;

    public AsyncProbe(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * Reports what this worker thread sees: the tenant in context, its own thread name
     * (so the test can prove the work really left the caller's thread), and the row count
     * an actual query returns from here.
     */
    @Async
    public CompletableFuture<AsyncObservation> observe() {
        String tenant = TenantContext.current().map(TenantScope::subject).orElse(null);
        String threadName = Thread.currentThread().getName();
        long visibleRows = documents.count();
        return CompletableFuture.completedFuture(
                new AsyncObservation(tenant, threadName, visibleRows));
    }
}
