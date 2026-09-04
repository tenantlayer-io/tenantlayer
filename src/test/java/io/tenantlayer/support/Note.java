package io.tenantlayer.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * Feature 20 — the discriminator strategy. {@code @TenantId} makes Hibernate add the
 * predicate on reads and set the column on writes; the application never mentions it.
 *
 * The {@code notes} table has no RLS policy, so this entity is the only thing standing
 * between one tenant and another's rows.
 */
@Entity
@Table(name = "notes")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    private String tenantId;

    private String body;

    protected Note() {
    }

    public Note(String body) {
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getBody() {
        return body;
    }
}
