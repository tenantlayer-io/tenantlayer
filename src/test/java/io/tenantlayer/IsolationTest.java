package io.tenantlayer;

import static io.tenantlayer.test.IsolationAssertions.assertTenantCannotSee;
import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.support.Document;
import io.tenantlayer.support.DocumentRepository;
import io.tenantlayer.support.TenantLayerTestBase;
import io.tenantlayer.test.WithTenant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PROOF 1 — isolation is a property of the connection, not of the query.
 *
 * findAll() emits "select ... from documents" with no tenant predicate. Nobody wrote a
 * filter; Hibernate did not add one. The rows come back scoped because Postgres applies
 * the policy. That is the entire product thesis in one test.
 */
class IsolationTest extends TenantLayerTestBase {

    @Autowired
    private DocumentRepository documents;

    @Test
    @WithTenant("acme")
    @DisplayName("the ORM emits no tenant predicate, yet only this tenant's rows come back")
    void ormEmitsNoTenantPredicateYetRowsAreScoped() {
        List<Document> visible = documents.findAll();

        assertThat(visible)
                .as("acme's own rows must be readable")
                .isNotEmpty();
        assertThat(visible)
                .extracting(Document::getTenantId)
                .as("nothing outside the acting tenant may appear")
                .containsOnly("acme");

        assertTenantCannotSee("globex");
    }
}
