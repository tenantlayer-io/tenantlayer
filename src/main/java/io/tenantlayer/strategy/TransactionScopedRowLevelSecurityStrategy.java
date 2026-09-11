package io.tenantlayer.strategy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opt-in row-level security strategy for transaction-pooling proxies such as PgBouncer.
 *
 * <p>The tenant value is written with {@code set_config(..., true)} after the transaction
 * manager has established the real JDBC transaction. PostgreSQL then removes it at commit or
 * rollback, before a transaction-pool backend can be assigned to another logical client.
 *
 * <p>This strategy deliberately wraps only {@link Connection}. It does not proxy statements,
 * result sets, metadata, or vendor interfaces, and it delegates {@link Connection#unwrap(Class)}
 * after the transaction has been bound. The connection wrapper exists only to provide a
 * framework-neutral first-use fallback for a manually constructed JDBC transaction manager and
 * to reject tenant-scoped work attempted in auto-commit mode. Spring-managed transaction
 * managers are bound at their transaction lifecycle callback instead.
 */
public class TransactionScopedRowLevelSecurityStrategy implements TenantConnectionStrategy {

    private static final String RESET_SQL =
            "select set_config('" + RowLevelSecurityStrategy.TENANT_SETTING + "', '', false)";
    private static final String APPLY_SQL =
            "select set_config('" + RowLevelSecurityStrategy.TENANT_SETTING + "', ?, true)";

    private final DataSource dataSource;

    public TransactionScopedRowLevelSecurityStrategy(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(dataSource.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(dataSource.getConnection(username, password));
    }

    @Override
    public String name() {
        return "ROW_LEVEL_SECURITY_TRANSACTION_SCOPED";
    }

    /**
     * Listener installed on Spring's transaction manager when it owns this DataSource.
     *
     * <p>{@code afterBegin} runs after {@code DataSourceTransactionManager} or
     * {@code JpaTransactionManager} has applied the transaction definition, including isolation
     * level, and after its JDBC connection has been bound in
     * {@link TransactionSynchronizationManager}. That ordering is why this is safer than
     * treating {@code setAutoCommit(false)} as the complete transaction boundary.
     */
    public TransactionExecutionListener transactionExecutionListener(DataSource transactionDataSource) {
        return new TransactionExecutionListener() {
            @Override
            public void afterBegin(TransactionExecution execution, Throwable failure) {
                if (failure != null || !execution.isNewTransaction()) {
                    return;
                }
                if (!TransactionSynchronizationManager.hasResource(transactionDataSource)) {
                    throw new IllegalStateException(
                            "transaction-scoped row-level security requires a JDBC connection "
                                    + "bound by the transaction manager");
                }
                try {
                    Connection connection = DataSourceUtils.getConnection(transactionDataSource);
                    bind(connection);
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "could not bind the transaction-scoped tenant", e);
                }
            }
        };
    }

    private Connection wrap(Connection delegate) throws SQLException {
        try {
            // This is not tenant publication. It ensures that a connection returned to this
            // strategy cannot carry session state from another implementation or pool client.
            clearSessionTenant(delegate);
            ConnectionHandler handler = new ConnectionHandler(delegate);
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    TransactionScopedRowLevelSecurityStrategy.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    handler);
            return proxy;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(delegate);
            throw e;
        }
    }

    private static void clearSessionTenant(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESET_SQL)) {
            statement.execute();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the failure that prevented a safely prepared connection from returning.
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void bind(Connection connection) throws SQLException {
        if (Proxy.isProxyClass(connection.getClass())
                && Proxy.getInvocationHandler(connection) instanceof ConnectionHandler handler) {
            handler.bindNow();
            return;
        }
        String tenant = TenantContext.current().map(TenantScope::subject).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
            statement.setString(1, tenant);
            statement.execute();
        }
    }

    private final class ConnectionHandler implements InvocationHandler {

        private final Connection delegate;
        private boolean bound;

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("setAutoCommit".equals(name)) {
                Object result = TransactionScopedRowLevelSecurityStrategy.invoke(
                        delegate, method, args);
                bound = false;
                return result;
            }
            if (("commit".equals(name) || "rollback".equals(name))
                    && method.getParameterCount() == 0) {
                try {
                    return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
                } finally {
                    bound = false;
                }
            }
            if ("close".equals(name)) {
                bound = false;
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("createStatement".equals(name)
                    || "prepareStatement".equals(name)
                    || "prepareCall".equals(name)
                    || "getMetaData".equals(name)
                    || "unwrap".equals(name)) {
                bindIfTransactional();
            }
            Object result = TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            if ("getMetaData".equals(name) && result instanceof DatabaseMetaData metadata) {
                return wrapMetadata(metadata, (Connection) ignored);
            }
            return result;
        }

        private DatabaseMetaData wrapMetadata(DatabaseMetaData metadata, Connection connection) {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    TransactionScopedRowLevelSecurityStrategy.class.getClassLoader(),
                    new Class<?>[] {DatabaseMetaData.class},
                    (ignored, method, args) -> {
                        if ("getConnection".equals(method.getName())
                                && method.getParameterCount() == 0) {
                            return connection;
                        }
                        return TransactionScopedRowLevelSecurityStrategy.invoke(metadata, method, args);
                    });
        }

        private void bindNow() throws SQLException {
            if (bound) {
                return;
            }
            if (delegate.getAutoCommit()) {
                if (TenantContext.current().isPresent()) {
                    throw new IllegalStateException(
                            "transaction-scoped row-level security requires an active transaction");
                }
                return;
            }
            String tenant = TenantContext.current().map(TenantScope::subject).orElse("");
            try (PreparedStatement statement = delegate.prepareStatement(APPLY_SQL)) {
                statement.setString(1, tenant);
                statement.execute();
                bound = true;
            }
        }

        private void bindIfTransactional() throws SQLException {
            if (delegate.getAutoCommit()) {
                // Shared infrastructure such as JdbcTenantRegistry is intentionally readable
                // without a tenant. Its own table is not tenant-scoped, while tenant-scoped
                // tables remain invisible because checkout cleared the GUC to "".
                if (TenantContext.current().isPresent()) {
                    throw new IllegalStateException(
                            "transaction-scoped row-level security requires an active transaction");
                }
                return;
            }
            bindNow();
        }

    }
}
