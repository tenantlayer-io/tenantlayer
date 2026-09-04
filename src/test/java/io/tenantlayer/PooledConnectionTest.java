package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.support.TenantLayerTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PROOF 2 — the differentiated one.
 *
 * The pool is capped at one connection, so the second query is guaranteed to run on the
 * same physical session the first one used. If the tenant setting rides back into the
 * pool, an unauthenticated request inherits it and reads someone else's data. The correct
 * behaviour is zero rows, not "whoever was here last".
 */
class PooledConnectionTest extends TenantLayerTestBase {

    @Test
    @DisplayName("a recycled connection fails closed, it does not inherit the last tenant")
    void recycledConnectionFailsClosed() {
        long asAcme = TenantContext.callWithTenant(TenantScope.of("acme"), this::countVisibleRows);

        assertThat(asAcme)
                .as("acme must see its own two rows, otherwise the rest of this test is vacuous")
                .isEqualTo(2);

        long withNoTenant = countVisibleRows();

        assertThat(withNoTenant)
                .as("the connection went back to the pool still carrying a tenant")
                .isZero();
    }

    private long countVisibleRows() {
        try (Connection connection = applicationDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select count(*) from documents");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
