package com.acme.orders;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * An ordinary cached service. Note what is absent: the method takes no arguments, so every
 * tenant asks for the identical cache key.
 *
 * That is the dangerous shape, and it is the common one — a per-tenant dashboard count, a
 * settings lookup, a feature list. Nothing here mentions tenancy, and nothing here could
 * be expected to: a cache hit never reaches the database, so row-level security is not
 * consulted and cannot help.
 */
@Service
public class OrderStatsService {

    private final OrderRepository orders;

    public OrderStatsService(OrderRepository orders) {
        this.orders = orders;
    }

    @Cacheable("orderStats")
    public long orderCount() {
        return orders.count();
    }
}
