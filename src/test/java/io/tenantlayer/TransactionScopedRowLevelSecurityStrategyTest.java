package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.TransactionScopedRowLevelSecurityStrategy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionScopedRowLevelSecurityStrategyTest {

    private static final String APPLY_SQL =
            "select set_config('tenantlayer.tenant', ?, true)";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void doesNotBindAtCheckoutButBindsAtTransactionStart() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement binding = mock(PreparedStatement.class);
        DataSource dataSource = dataSource(delegate, binding);

        TenantContext.enter(TenantScope.of("acme"));
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(dataSource)
                .getConnection();

        verify(delegate, never()).prepareStatement(anyString());

        connection.setAutoCommit(false);

        verify(delegate).prepareStatement(APPLY_SQL);
        verify(binding).setString(1, "acme");
        verify(binding).execute();
    }

    @Test
    void rejectsAccessOutsideTransactionBeforeDelegating() throws Exception {
        Connection delegate = mock(Connection.class);
        DataSource dataSource = dataSource(delegate, mock(PreparedStatement.class));
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(dataSource)
                .getConnection();

        assertThatThrownBy(() -> connection.prepareStatement("select 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an active transaction");
        verify(delegate, never()).prepareStatement(anyString());
    }

    @Test
    void commitAndRollbackRequireAFreshTenantBinding() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement binding = mock(PreparedStatement.class);
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(
                dataSource(delegate, binding)).getConnection();

        TenantContext.enter(TenantScope.of("acme"));
        connection.setAutoCommit(false);
        connection.commit();

        TenantContext.enter(TenantScope.of("globex"));
        connection.setAutoCommit(false);
        connection.rollback();

        verify(binding).setString(1, "acme");
        verify(binding).setString(1, "globex");
    }

    private DataSource dataSource(Connection delegate, PreparedStatement binding)
            throws Exception {
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.prepareStatement(anyString())).thenReturn(binding);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(delegate);
        return dataSource;
    }
}
