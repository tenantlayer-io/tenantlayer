package com.acme.orders;

import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the summary on a worker thread.
 *
 * Note it queries the database from that thread and still knows nothing about tenants —
 * if the tenant fails to cross the boundary, this returns zeros rather than the caller's
 * data, which is what the test checks.
 */
@Service
public class OrderSummaryService {

    private final OrderRepository orders;

    public OrderSummaryService(OrderRepository orders) {
        this.orders = orders;
    }

    @Async
    public CompletableFuture<OrderSummary> computeAsync() {
        long count = orders.count();
        long total = orders.findAll().stream().mapToLong(Order::getAmountCents).sum();
        return CompletableFuture.completedFuture(new OrderSummary(count, total));
    }
}
