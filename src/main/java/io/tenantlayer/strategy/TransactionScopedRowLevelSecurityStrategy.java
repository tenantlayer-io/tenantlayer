package io.tenantlayer.strategy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Opt-in row-level security strategy for transaction-pooling proxies such as PgBouncer.
 *
 * <p>The tenant is not published when a connection is checked out. Spring's JDBC transaction
 * managers disable auto-commit before they run the first statement, so the returned connection
 * observes that transition and publishes the tenant with {@code is_local = true}. PostgreSQL
 * then removes the setting at commit or rollback before the physical connection can be handed
 * to another transaction.
 *
 * <p>Statements are refused until that transaction transition has happened. This is deliberate:
 * an autocommit statement has no transaction-local scope, and allowing it would turn a missing
 * transaction into an unscoped read. The strategy therefore requires JDBC/Spring transaction
 * demarcation and does not support bypassing the wrapper with {@link Connection#unwrap(Class)}.
 */
public class TransactionScopedRowLevelSecurityStrategy implements TenantConnectionStrategy {

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

    private Connection wrap(Connection delegate) throws SQLException {
        ConnectionHandler handler = new ConnectionHandler(delegate);
        Connection proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler);
        handler.attach(proxy);
        try {
            // A DataSource normally returns auto-commit connections. Accommodate a delegate
            // configured otherwise without handing out a connection whose first transaction
            // would be unbound.
            if (!delegate.getAutoCommit()) {
                handler.beginTransaction();
            }
            return proxy;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(delegate);
            throw e;
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

    private final class ConnectionHandler implements InvocationHandler {

        private final Connection delegate;
        private Connection proxy;
        private boolean transactionActive;

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        private void attach(Connection proxy) {
            this.proxy = proxy;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("unwrap".equals(name)) {
                throw new SQLException(
                        "transaction-scoped tenant connections cannot be unwrapped");
            }
            if ("isWrapperFor".equals(name)) {
                return false;
            }
            if ("setAutoCommit".equals(name) && Boolean.FALSE.equals(args[0])) {
                Object result = TransactionScopedRowLevelSecurityStrategy.invoke(
                        delegate, method, args);
                if (!transactionActive) {
                    beginTransaction();
                }
                return result;
            }
            if ("setAutoCommit".equals(name) && Boolean.TRUE.equals(args[0])) {
                try {
                    return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
                } finally {
                    transactionActive = false;
                }
            }
            if ("commit".equals(name) && method.getParameterCount() == 0) {
                try {
                    return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
                } finally {
                    transactionActive = false;
                }
            }
            if ("rollback".equals(name) && method.getParameterCount() == 0) {
                try {
                    return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
                } finally {
                    transactionActive = false;
                }
            }
            if ("close".equals(name)) {
                if (transactionActive) {
                    try {
                        delegate.rollback();
                    } catch (SQLException rollbackFailure) {
                        // Closing is still required; the pool will discard or reset the handle.
                    } finally {
                        transactionActive = false;
                    }
                }
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("rollback".equals(name) && method.getParameterCount() == 1
                    && args[0] instanceof Savepoint) {
                // A savepoint rollback does not end the surrounding transaction.
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if (isConnectionControlMethod(name)) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            ensureTransaction();
            Object result = TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            if (result instanceof Statement statement) {
                return wrapStatement(statement, this);
            }
            return result;
        }

        private void beginTransaction() throws SQLException {
            if (transactionActive) {
                return;
            }
            try (PreparedStatement statement = delegate.prepareStatement(APPLY_SQL)) {
                TenantScope scope = TenantContext.require();
                statement.setString(1, scope.subject());
                statement.execute();
                transactionActive = true;
            } catch (SQLException | RuntimeException e) {
                closeQuietly(delegate);
                throw e;
            }
        }

        private void ensureTransaction() {
            if (!transactionActive) {
                throw new IllegalStateException(
                        "transaction-scoped row-level security requires an active transaction");
            }
        }

        private Connection proxy() {
            return proxy;
        }
    }

    private static boolean isConnectionControlMethod(String name) {
        return switch (name) {
            case "getAutoCommit", "isClosed", "isValid", "setReadOnly", "isReadOnly",
                    "setCatalog", "getCatalog", "setTransactionIsolation", "getTransactionIsolation",
                    "setSchema", "getSchema", "setNetworkTimeout", "getNetworkTimeout", "abort",
                    "getWarnings", "clearWarnings", "setHoldability", "getHoldability",
                    "setClientInfo", "getClientInfo" -> true;
            default -> false;
        };
    }

    private Statement wrapStatement(Statement statement, ConnectionHandler owner) {
        Class<?> contract = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        StatementHandler handler = new StatementHandler(statement, owner);
        Statement proxy = (Statement) Proxy.newProxyInstance(
                contract.getClassLoader(), new Class<?>[] {contract}, handler);
        handler.attach(proxy);
        return proxy;
    }

    private final class StatementHandler implements InvocationHandler {

        private final Statement delegate;
        private final ConnectionHandler owner;
        private Statement proxy;

        private StatementHandler(Statement delegate, ConnectionHandler owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        private void attach(Statement proxy) {
            this.proxy = proxy;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("unwrap".equals(name)) {
                throw new SQLException(
                        "transaction-scoped tenant statements cannot be unwrapped");
            }
            if ("isWrapperFor".equals(name)) {
                return false;
            }
            if ("getConnection".equals(name)) {
                return owner.proxy();
            }
            if ("close".equals(name) || "isClosed".equals(name)
                    || "getWarnings".equals(name) || "clearWarnings".equals(name)) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            owner.ensureTransaction();
            Object result = TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            if (result instanceof ResultSet resultSet) {
                return wrapResultSet(resultSet, owner, proxy);
            }
            return result;
        }
    }

    private ResultSet wrapResultSet(
            ResultSet resultSet, ConnectionHandler owner, Statement statement) {
        ResultSetHandler handler = new ResultSetHandler(resultSet, owner, statement);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    private final class ResultSetHandler implements InvocationHandler {

        private final ResultSet delegate;
        private final ConnectionHandler owner;
        private final Statement statement;

        private ResultSetHandler(
                ResultSet delegate, ConnectionHandler owner, Statement statement) {
            this.delegate = delegate;
            this.owner = owner;
            this.statement = statement;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            if ("getStatement".equals(name)) {
                return statement;
            }
            if ("unwrap".equals(name)) {
                throw new SQLException(
                        "transaction-scoped tenant result sets cannot be unwrapped");
            }
            if ("isWrapperFor".equals(name)) {
                return false;
            }
            if ("close".equals(name) || "isClosed".equals(name)
                    || "getWarnings".equals(name) || "clearWarnings".equals(name)) {
                return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
            }
            owner.ensureTransaction();
            return TransactionScopedRowLevelSecurityStrategy.invoke(delegate, method, args);
        }
    }
}
