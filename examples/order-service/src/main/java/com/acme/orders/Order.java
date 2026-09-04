package com.acme.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customer;

    private String item;

    @Column(name = "amount_cents")
    private long amountCents;

    private String status;

    @Generated(event = EventType.INSERT)
    @Column(name = "placed_at", insertable = false, updatable = false)
    private Instant placedAt;

    /**
     * Never written by this application. The column defaults to the tenant on the
     * connection, so the database stamps ownership and the service does not have to
     * remember to. Mapped only so it can be shown in responses.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private String tenantId;

    protected Order() {
    }

    public Order(String customer, String item, long amountCents) {
        this.customer = customer;
        this.item = item;
        this.amountCents = amountCents;
        this.status = "PLACED";
    }

    public Long getId() {
        return id;
    }

    public String getCustomer() {
        return customer;
    }

    public String getItem() {
        return item;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getStatus() {
        return status;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public String getTenantId() {
        return tenantId;
    }
}
