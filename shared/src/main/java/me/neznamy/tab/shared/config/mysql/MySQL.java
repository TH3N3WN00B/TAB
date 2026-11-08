package me.neznamy.tab.shared.config.mysql;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;
import java.sql.*;
import java.util.Properties;

@RequiredArgsConstructor
public class MySQL {

    private volatile Connection con;
    @NotNull private final MySQLConfiguration configuration;

    /** Flag tracking whether connection was closed on purpose, preventing it from being silently reopened */
    private volatile boolean closed;

    /** Flag tracking whether a transaction is currently in progress */
    private boolean inTransaction;

    public synchronized void openConnection() throws SQLException {
        if (closed) throw new SQLException("MySQL connection has already been closed");
        if (isConnected()) return;
        Properties properties = new Properties();
        properties.setProperty("user", configuration.getUsername());
        properties.setProperty("password", configuration.getPassword());
        properties.setProperty("useSSL", String.valueOf(configuration.isUseSSL()));
        properties.setProperty("characterEncoding", "UTF-8");
        con = DriverManager.getConnection(String.format("jdbc:mysql://%s:%d/%s", configuration.getHost(), configuration.getPort(), configuration.getDatabase()), properties);
        TAB.getInstance().getPlatform().logInfo(new TabTextComponent("Successfully connected to MySQL", TabTextColor.GREEN));
    }

    /**
     * Closes the connection. Once closed, the connection will not be reopened
     * by any further queries, which will fail instead.
     *
     * @throws  SQLException
     *          If closing the connection fails
     */
    public void closeConnection() throws SQLException {
        closed = true;
        Connection connection = con;
        if (connection != null && !connection.isClosed()) connection.close();
    }

    private boolean isConnected() throws SQLException {
        return con != null && !con.isClosed();
    }

    public synchronized void execute(@NonNull String query, @Nullable Object... vars) throws SQLException {
        try (PreparedStatement ps = prepareStatement(query, vars)) {
            ps.execute();
        }
    }

    /**
     * Runs given task as a single transaction. If the task throws anything,
     * all changes made by it are rolled back and the error is re-thrown.
     * While the transaction is running, no other thread can use this connection.
     *
     * @param   transaction
     *          Task to run in a transaction
     * @throws  SQLException
     *          If any query fails or the transaction could not be committed
     */
    public synchronized void executeInTransaction(@NonNull Transaction transaction) throws SQLException {
        if (inTransaction) {
            // Already in a transaction, simply join it
            transaction.run();
            return;
        }
        if (!isConnected()) openConnection();
        Connection connection = con;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        inTransaction = true;
        try {
            transaction.run();
            connection.commit();
        } catch (Throwable t) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                t.addSuppressed(e);
            }
            throw t;
        } finally {
            inTransaction = false;
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // Connection is broken, it will be reopened on next query
            }
        }
    }

    @NotNull
    private PreparedStatement prepareStatement(@NonNull String query, @Nullable Object... vars) throws SQLException {
        if (!isConnected()) {
            // Reopening the connection in the middle of a transaction would silently auto-commit the rest of it
            if (inTransaction) throw new SQLException("MySQL connection was lost during a transaction");
            openConnection();
        }
        PreparedStatement ps = con.prepareStatement(query);
        int i = 0;
        if (query.contains("?") && vars != null) {
            for (Object obj : vars) {
                i++;
                ps.setObject(i, obj);
            }
        }
        return ps;
    }

    @NotNull
    public synchronized CachedRowSet getCRS(@NonNull String query, @NonNull Object... vars) throws SQLException {
        try (PreparedStatement ps = prepareStatement(query, vars); ResultSet rs = ps.executeQuery()) {
            CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
            crs.populate(rs);
            return crs;
        }
    }

    /**
     * Database work to run in a transaction using {@link #executeInTransaction(Transaction)}.
     */
    @FunctionalInterface
    public interface Transaction {

        /**
         * Runs the database work.
         *
         * @throws  SQLException
         *          If any query fails
         */
        void run() throws SQLException;
    }
}