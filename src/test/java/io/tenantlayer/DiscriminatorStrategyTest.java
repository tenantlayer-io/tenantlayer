package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.support.Note;
import io.tenantlayer.support.NoteRepository;
import io.tenantlayer.support.PostgresSupport;
import io.tenantlayer.support.TenantLayerTestBase;
import io.tenantlayer.test.WithTenant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Feature 20 — the discriminator strategy, on a table with no RLS policy.
 *
 * That detail is the whole design of this test. Every other isolation test in this suite
 * runs against {@code documents}, where Postgres would scope the rows even if Hibernate
 * did nothing at all — which means those tests cannot tell whether {@code @TenantId}
 * works. {@code notes} has no policy, so if the discriminator is not doing its job, acme
 * reads globex's rows and this fails.
 */
class DiscriminatorStrategyTest extends TenantLayerTestBase {

    @Autowired
    private NoteRepository notes;

    /**
     * Per test, not per class: one of these tests inserts a note, and a row count is only
     * evidence of isolation when you know exactly what was seeded.
     */
    @BeforeEach
    void reseed() {
        PostgresSupport.resetSchema();
    }

    @Test
    @WithTenant("acme")
    @DisplayName("@TenantId scopes reads on a table Postgres is not protecting")
    void tenantIdScopesReadsWithoutRls() {
        List<Note> visible = notes.findAll();

        assertThat(visible)
                .as("acme's own notes must be readable, or this test proves nothing")
                .isNotEmpty();
        assertThat(visible)
                .extracting(Note::getTenantId)
                .as("nothing but Hibernate is filtering this table")
                .containsOnly("acme");
        assertThat(visible).hasSize(2);
    }

    @Test
    @WithTenant("globex")
    @DisplayName("a different tenant sees a different set on the same table")
    void anotherTenantSeesItsOwn() {
        assertThat(notes.findAll())
                .extracting(Note::getTenantId)
                .containsOnly("globex")
                .hasSize(3);
    }

    @Test
    @WithTenant("acme")
    @DisplayName("the tenant column is stamped on write without the application setting it")
    void tenantIsStampedOnWrite() {
        Note saved = notes.save(new Note("acme note three"));

        assertThat(saved.getTenantId())
                .as("the application never assigned this")
                .isEqualTo("acme");
        assertThat(notes.findAll())
                .extracting(Note::getBody)
                .contains("acme note three")
                .hasSize(3);
    }

    @Test
    @WithTenant("acme")
    @DisplayName("a row belonging to another tenant is not reachable by id")
    void otherTenantsRowIsNotReachableById() {
        Long globexNoteId = globexNoteId();

        assertThat(notes.findById(globexNoteId))
                .as("fetching by primary key must still respect the discriminator")
                .isEmpty();
    }

    private Long globexNoteId() {
        try (var connection = PostgresSupport.privileged().getConnection();
             var statement = connection.prepareStatement(
                     "select id from notes where tenant_id = 'globex' order by id limit 1");
             var rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
