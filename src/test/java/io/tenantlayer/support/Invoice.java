package io.tenantlayer.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Feature 30's subject. A plain tenant column and no {@code @TenantId}, so nothing filters
 * this entity until the generated policy is applied — which is exactly what the generation
 * test needs in order to show a difference.
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String reference;

    protected Invoice() {
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getReference() {
        return reference;
    }
}
