package top.sshh.bililiverecoder.config.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** 所有业务连接都经过这里，维护时先等连接归还，再暂停连接池 */
public class MaintenanceDataSource extends DelegatingDataSource implements AutoCloseable {
    private final DatabaseMaintenanceState state;
    private volatile HikariDataSource pool;
    private HikariConfig restartConfig;

    public MaintenanceDataSource(HikariDataSource pool, DatabaseMaintenanceState state) {
        super(pool);
        this.pool = pool;
        this.state = state;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return borrow(null, null);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return borrow(username, password);
    }

    private Connection borrow(String username, String password) throws SQLException {
        Thread borrower = state.acquireConnection();
        try {
            Connection connection = username == null ? pool.getConnection() : pool.getConnection(username, password);
            AtomicBoolean returned = new AtomicBoolean();
            try {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (name) {
                                case "equals" -> proxy == arguments[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> connection.toString();
                                default -> throw new UnsupportedOperationException(name);
                            };
                        }
                        if ("close".equals(name) || "abort".equals(name)) {
                            if (!returned.compareAndSet(false, true)) return null;
                            try {
                                return method.invoke(connection, arguments);
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            } finally {
                                state.releaseConnection(borrower);
                            }
                        }
                        if ("unwrap".equals(name) && arguments[0] == Connection.class) return proxy;
                        if ("isWrapperFor".equals(name) && arguments[0] == Connection.class) return true;
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            } catch (RuntimeException | Error error) {
                try {
                    connection.close();
                } catch (SQLException closeError) {
                    error.addSuppressed(closeError);
                }
                throw error;
            }
        } catch (SQLException | RuntimeException | Error error) {
            state.releaseConnection(borrower);
            throw error;
        }
    }

    public synchronized void closePoolForMaintenance() throws SQLException {
        stopPoolForMaintenance();
        try (Connection connection = openDirect(restartConfig.getJdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    public synchronized void stopPoolForMaintenance() {
        state.requireMaintenanceOwner();
        restartConfig = new HikariConfig();
        pool.copyStateTo(restartConfig);
        // 完全停止连接池，避免后台补充连接时又把原库打开
        pool.close();
    }

    public synchronized void reopenPool() {
        state.requireMaintenanceOwner();
        if (restartConfig == null || !pool.isClosed()) return;
        HikariDataSource replacement = new HikariDataSource(restartConfig);
        pool = replacement;
        setTargetDataSource(replacement);
    }

    public Connection openDirect(String jdbcUrl) throws SQLException {
        Properties properties = new Properties();
        properties.putAll(pool.getDataSourceProperties());
        if (pool.getUsername() != null) properties.setProperty("user", pool.getUsername());
        if (pool.getPassword() != null) properties.setProperty("password", pool.getPassword());
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    public String jdbcUrl() {
        return pool.getJdbcUrl();
    }

    @Override
    public synchronized void close() {
        state.stop();
        pool.close();
    }
}
