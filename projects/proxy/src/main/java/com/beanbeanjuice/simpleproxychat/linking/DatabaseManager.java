package com.beanbeanjuice.simpleproxychat.linking;

import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PostgreSQL connection pool and schema initializer for account linking.
 */
public class DatabaseManager implements AutoCloseable {

    private final Config config;
    private HikariDataSource dataSource;
    private boolean enabled;

    public DatabaseManager(Config config) {
        this.config = config;
    }

    private boolean isUndefinedDatabase(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException se) {
                String state = se.getSQLState();
                if (state != null && state.equals("3D000")) { // undefined_database
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private boolean createDatabase(String host, int port, String db, String user, String pass, boolean ssl) {
        // Connect to maintenance DB 'postgres' and create the target database.
        String adminUrl = String.format("jdbc:postgresql://%s:%d/postgres", host, port);
        if (ssl) adminUrl += "?ssl=true";
        try (Connection con = DriverManager.getConnection(adminUrl, user, pass); Statement st = con.createStatement()) {
            // Avoid quoting to keep default lowercasing semantics
            st.executeUpdate("CREATE DATABASE " + db + " ENCODING 'UTF8'");
            return true;
        } catch (SQLException se) {
            // If someone else created it between our checks, treat as success
            String state = se.getSQLState();
            if (state != null && state.equals("42P04")) { // duplicate_database
                return true;
            }
            return false;
        }
    }

    public void init() {
        this.enabled = Boolean.TRUE.equals(config.get(ConfigKey.DATABASE_POSTGRES_ENABLED).asBoolean());
        if (!enabled) return;

        final String host = config.get(ConfigKey.DATABASE_POSTGRES_HOST).asString();
        final int port = config.get(ConfigKey.DATABASE_POSTGRES_PORT).asInt();
        final String db = config.get(ConfigKey.DATABASE_POSTGRES_DATABASE).asString();
        final String user = config.get(ConfigKey.DATABASE_POSTGRES_USERNAME).asString();
        final String pass = config.get(ConfigKey.DATABASE_POSTGRES_PASSWORD).asString();
        final boolean ssl = Boolean.TRUE.equals(config.get(ConfigKey.DATABASE_POSTGRES_SSL).asBoolean());

        HikariConfig hc = new HikariConfig();
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, db);
        if (ssl) jdbcUrl += "?ssl=true";
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(user);
        hc.setPassword(pass);
        // Ensure driver is found even when shaded/relocated without triggering Hikari error logs
        boolean driverConfigured = false;
        try {
            Class.forName("com.beanbeanjuice.simpleproxychat.libs.org.postgresql.Driver");
            hc.setDriverClassName("com.beanbeanjuice.simpleproxychat.libs.org.postgresql.Driver");
            driverConfigured = true;
        } catch (Throwable ignored) { }
        if (!driverConfigured) {
            try {
                Class.forName("org.postgresql.Driver");
                hc.setDriverClassName("org.postgresql.Driver");
                driverConfigured = true;
            } catch (Throwable ignored) { }
        }

        try { hc.setMaximumPoolSize(config.get(ConfigKey.DATABASE_POSTGRES_POOL_MAXIMUM_POOL_SIZE).asInt()); } catch (Throwable ignored) {}
        try { hc.setMinimumIdle(config.get(ConfigKey.DATABASE_POSTGRES_POOL_MINIMUM_IDLE).asInt()); } catch (Throwable ignored) {}
        try { hc.setConnectionTimeout(config.get(ConfigKey.DATABASE_POSTGRES_POOL_CONNECTION_TIMEOUT_MS).asInt()); } catch (Throwable ignored) {}
        try { hc.setIdleTimeout(config.get(ConfigKey.DATABASE_POSTGRES_POOL_IDLE_TIMEOUT_MS).asInt()); } catch (Throwable ignored) {}
        try { hc.setMaxLifetime(config.get(ConfigKey.DATABASE_POSTGRES_POOL_MAX_LIFETIME_MS).asInt()); } catch (Throwable ignored) {}

        try {
            this.dataSource = new HikariDataSource(hc);
        } catch (RuntimeException e) {
            // If database is missing, attempt to create it and retry once
            if (isUndefinedDatabase(e)) {
                if (createDatabase(host, port, db, user, pass, ssl)) {
                    // Retry pool init once after creating DB
                    try {
                        this.dataSource = new HikariDataSource(hc);
                    } catch (RuntimeException e2) {
                        closeQuietly();
                        this.enabled = false;
                        return;
                    }
                } else {
                    closeQuietly();
                    this.enabled = false;
                    return;
                }
            } else {
                // Connection failed (e.g., network/credentials). Disable linking gracefully.
                closeQuietly();
                this.enabled = false;
                return;
            }
        }

        try {
            initSchema();
        } catch (SQLException e) {
            // Disable on schema init failure
            closeQuietly();
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled && dataSource != null;
    }

    public Connection getConnection() throws SQLException {
        if (!isEnabled()) throw new SQLException("Database not enabled");
        return dataSource.getConnection();
    }

    private void initSchema() throws SQLException {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            // Discord<->Minecraft associations
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS account_links (
                    mc_uuid UUID PRIMARY KEY,
                    discord_id VARCHAR(32) UNIQUE NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Generated link codes pending redemption
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS link_codes (
                    code VARCHAR(32) PRIMARY KEY,
                    mc_uuid UUID NOT NULL,
                    mc_username VARCHAR(32) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMPTZ NOT NULL,
                    redeemed BOOLEAN NOT NULL DEFAULT FALSE,
                    redeemed_by_discord_id VARCHAR(32),
                    redeemed_at TIMESTAMPTZ
                )
                """);

            // Helpful indexes
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_link_codes_expires_at ON link_codes (expires_at)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_link_codes_mc_uuid ON link_codes (mc_uuid)");
        }
    }

    private void closeQuietly() {
        try { close(); } catch (Exception ignored) { }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            try { dataSource.close(); } finally { dataSource = null; }
        }
    }
}
