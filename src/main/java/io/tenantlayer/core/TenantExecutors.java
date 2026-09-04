package io.tenantlayer.core;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Feature 11 — carries the tenant across {@link java.util.concurrent.CompletableFuture}
 * composition and any hand-rolled executor.
 *
 * <h2>Why @Async alone is not enough</h2>
 *
 * The autoconfiguration decorates Spring's own task executors, which covers
 * {@code @Async}. It cannot cover an {@code ExecutorService} the application created
 * itself, and it cannot cover {@code CompletableFuture.supplyAsync(...)} with no executor
 * argument, which runs on the common ForkJoinPool — a pool Spring has never heard of.
 * Both are ordinary things to write, and both silently lose the tenant.
 *
 * <p>Losing it is not a null-pointer exception. The work continues on a thread with no
 * tenant bound, the connection it borrows is published with the empty tenant, and the
 * query returns zero rows. It reads as missing data, not as a bug.
 *
 * <h2>Capture at submit, restore after</h2>
 *
 * The scope is read on the calling thread when the task is <em>submitted</em>, not when it
 * runs — by then the request that submitted it may have completed and cleared its context.
 * Whatever the worker was carrying before is put back afterwards, because pool threads are
 * reused and a worker that keeps the last task's tenant is the same leak as a connection
 * that does.
 */
public final class TenantExecutors {

    private TenantExecutors() {
    }

    /** Wraps an executor so every task submitted through it keeps the submitter's tenant. */
    public static Executor wrap(Executor delegate) {
        return command -> delegate.execute(runnable(command));
    }

    /** Wraps an executor service, including its {@code invokeAll} and {@code submit} forms. */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TenantAwareExecutorService(delegate);
    }

    public static Runnable runnable(Runnable task) {
        TenantScope captured = TenantContext.current().orElse(null);
        return () -> {
            TenantScope previous = TenantContext.current().orElse(null);
            TenantContext.enter(captured);
            try {
                task.run();
            } finally {
                TenantContext.exit(previous);
            }
        };
    }

    public static <T> Callable<T> callable(Callable<T> task) {
        TenantScope captured = TenantContext.current().orElse(null);
        return () -> {
            TenantScope previous = TenantContext.current().orElse(null);
            TenantContext.enter(captured);
            try {
                return task.call();
            } finally {
                TenantContext.exit(previous);
            }
        };
    }

    /** For {@code CompletableFuture.supplyAsync(TenantExecutors.supplier(() -> ...))}. */
    public static <T> Supplier<T> supplier(Supplier<T> task) {
        TenantScope captured = TenantContext.current().orElse(null);
        return () -> {
            TenantScope previous = TenantContext.current().orElse(null);
            TenantContext.enter(captured);
            try {
                return task.get();
            } finally {
                TenantContext.exit(previous);
            }
        };
    }

    private record TenantAwareExecutorService(ExecutorService delegate) implements ExecutorService {

        @Override
        public void execute(Runnable command) {
            delegate.execute(runnable(command));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(runnable(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(runnable(task), result);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(callable(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(captureAll(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(captureAll(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, java.util.concurrent.ExecutionException {
            return delegate.invokeAny(captureAll(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException,
                java.util.concurrent.TimeoutException {
            return delegate.invokeAny(captureAll(tasks), timeout, unit);
        }

        private <T> List<Callable<T>> captureAll(Collection<? extends Callable<T>> tasks) {
            return tasks.stream().map(TenantExecutors::<T>callable).toList();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
