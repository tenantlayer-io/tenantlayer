package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionScopedRowLevelSecurityStrategyTest {

    private static final String APPLY_SQL =
            "select set_config('tenantlayer.tenant', ?, true)";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void doesNotBindAtCheckoutButBindsBeforeFirstStatement() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement binding = mock(PreparedStatement.class);
        DataSource dataSource = dataSource(delegate, binding);

        TenantContext.enter(TenantScope.of("acme"));
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(dataSource)
                .getConnection();

        verify(delegate, never()).prepareStatement(APPLY_SQL);

        connection.setAutoCommit(false);

        verify(delegate, never()).prepareStatement(APPLY_SQL);

        connection.prepareStatement("select 1");

        verify(delegate).prepareStatement(APPLY_SQL);
        verify(binding).setString(1, "acme");
        verify(binding, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void rejectsAccessOutsideTransactionBeforeDelegating() throws Exception {
        Connection delegate = mock(Connection.class);
        DataSource dataSource = dataSource(delegate, mock(PreparedStatement.class));
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(dataSource)
                .getConnection();

        TenantContext.enter(TenantScope.of("acme"));

        assertThatThrownBy(() -> connection.prepareStatement("select 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an active transaction");
        verify(delegate, never()).prepareStatement("select 1");
    }

    @Test
    void commitAndRollbackRequireAFreshTenantBinding() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement binding = mock(PreparedStatement.class);
        Connection connection = new TransactionScopedRowLevelSecurityStrategy(
                dataSource(delegate, binding)).getConnection();

        TenantContext.enter(TenantScope.of("acme"));
        connection.setAutoCommit(false);
        connection.prepareStatement("select 1");
        connection.commit();

        TenantContext.enter(TenantScope.of("globex"));
        connection.setAutoCommit(false);
        connection.prepareStatement("select 2");
        connection.rollback();

        verify(binding).setString(1, "acme");
        verify(binding).setString(1, "globex");
    }

    @Test
    void springTransactionListenerBindsTheResourceAfterBegin() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement binding = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(APPLY_SQL)).thenReturn(binding);
        DataSource transactionDataSource = mock(DataSource.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(
                transactionDataSource, new ConnectionHolder(connection));
        try {
            TransactionExecution execution = mock(TransactionExecution.class);
            when(execution.isNewTransaction()).thenReturn(true);
            TenantContext.enter(TenantScope.of("acme"));

            new TransactionScopedRowLevelSecurityStrategy(transactionDataSource)
                    .transactionExecutionListener(transactionDataSource)
                    .afterBegin(execution, null);

            verify(binding).setString(1, "acme");
            verify(binding).execute();
        } finally {
            TenantContext.clear();
            TransactionSynchronizationManager.unbindResource(transactionDataSource);
            TransactionSynchronizationManager.clear();
        }
    }

    private DataSource dataSource(Connection delegate, PreparedStatement binding)
            throws Exception {
        when(delegate.getAutoCommit()).thenReturn(true);
        when(delegate.prepareStatement(anyString())).thenReturn(binding);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(delegate);
        doAnswer(invocation -> {
            when(delegate.getAutoCommit()).thenReturn(false);
            return null;
        }).when(delegate).setAutoCommit(false);
        return dataSource;
    }
}
