package com.acme.orders;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No findByTenantId. No @Query with a tenant predicate. Nothing here knows tenants exist.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
