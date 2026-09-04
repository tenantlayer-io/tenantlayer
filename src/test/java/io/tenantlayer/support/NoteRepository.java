package io.tenantlayer.support;

import org.springframework.data.jpa.repository.JpaRepository;

/** No findByTenantId. Nothing here mentions tenancy; that is the point. */
public interface NoteRepository extends JpaRepository<Note, Long> {
}
