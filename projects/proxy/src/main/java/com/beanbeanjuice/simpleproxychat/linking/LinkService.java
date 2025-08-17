package com.beanbeanjuice.simpleproxychat.linking;

import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;

import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Core linking logic: generate and redeem codes, query links.
 */
public class LinkService {

    public enum RedeemResult {
        SUCCESS,
        INVALID,
        EXPIRED,
        ALREADY_USED,
        ALREADY_LINKED,
        DB_ERROR
    }

    public enum UnlinkResult {
        SUCCESS,
        NOT_LINKED,
        DB_ERROR
    }

    private final DatabaseManager db;
    private final Config config;
    private final SecureRandom random = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no I/O/1/0

    public LinkService(DatabaseManager db, Config config) {
        this.db = db;
        this.config = config;
    }

    public boolean isEnabled() {
        return db != null && db.isEnabled() && Boolean.TRUE.equals(config.get(ConfigKey.LINKING_ENABLED).asBoolean());
    }

    public Optional<String> generateCode(UUID mcUuid, String mcUsername) {
        if (!isEnabled()) return Optional.empty();
        final int len = safeInt(ConfigKey.LINKING_CODE_LENGTH, 6);
        final int minutes = safeInt(ConfigKey.LINKING_EXPIRATION_MINUTES, 10);
        final Instant expiresAt = Instant.now().plus(Duration.ofMinutes(minutes));

        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Remove any previous unredeemed codes for this account
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM link_codes WHERE mc_uuid = ? AND redeemed = FALSE")) {
                    ps.setObject(1, mcUuid);
                    ps.executeUpdate();
                }

                // Attempt unique code generation with retries
                String code = null;
                for (int i = 0; i < 10; i++) {
                    String candidate = randomCode(len);
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO link_codes (code, mc_uuid, mc_username, expires_at) VALUES (?,?,?,?)")) {
                        ps.setString(1, candidate);
                        ps.setObject(2, mcUuid);
                        ps.setString(3, mcUsername);
                        ps.setTimestamp(4, Timestamp.from(expiresAt));
                        ps.executeUpdate();
                        code = candidate;
                        break;
                    } catch (SQLException ex) {
                        // Unique violation -> retry; otherwise rethrow
                        if (!isUniqueViolation(ex)) throw ex;
                    }
                }

                if (code == null) {
                    con.rollback();
                    return Optional.empty();
                }

                con.commit();
                return Optional.of(code);
            } catch (SQLException e) {
                try { con.rollback(); } catch (SQLException ignored) {}
                return Optional.empty();
            } finally {
                try { con.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public RedeemResult redeem(String code, String discordId) {
        if (!isEnabled()) return RedeemResult.DB_ERROR;
        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Lock the code row to avoid race conditions
                UUID mcUuid;
                Instant expiresAt;
                boolean redeemed;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT mc_uuid, expires_at, redeemed FROM link_codes WHERE code = ? FOR UPDATE")) {
                    ps.setString(1, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return RedeemResult.INVALID;
                        }
                        mcUuid = (UUID) rs.getObject(1);
                        expiresAt = rs.getTimestamp(2).toInstant();
                        redeemed = rs.getBoolean(3);
                    }
                }

                if (redeemed) {
                    con.rollback();
                    return RedeemResult.ALREADY_USED;
                }
                if (expiresAt.isBefore(Instant.now())) {
                    con.rollback();
                    return RedeemResult.EXPIRED;
                }

                // Check existing links for either side
                if (isDiscordLinked(con, discordId) || isMinecraftLinked(con, mcUuid)) {
                    con.rollback();
                    return RedeemResult.ALREADY_LINKED;
                }

                // Mark redeemed
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE link_codes SET redeemed = TRUE, redeemed_by_discord_id = ?, redeemed_at = CURRENT_TIMESTAMP WHERE code = ?")) {
                    ps.setString(1, discordId);
                    ps.setString(2, code);
                    ps.executeUpdate();
                }

                // Insert association
                int inserted;
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO account_links (mc_uuid, discord_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setObject(1, mcUuid);
                    ps.setString(2, discordId);
                    inserted = ps.executeUpdate();
                }

                if (inserted == 0) {
                    // Someone else linked concurrently
                    con.rollback();
                    return RedeemResult.ALREADY_LINKED;
                }

                con.commit();
                return RedeemResult.SUCCESS;
            } catch (SQLException e) {
                try { con.rollback(); } catch (SQLException ignored) {}
                return RedeemResult.DB_ERROR;
            } finally {
                try { con.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return RedeemResult.DB_ERROR;
        }
    }

    public boolean isDiscordLinked(String discordId) {
        if (!isEnabled()) return false;
        try (Connection con = db.getConnection()) {
            return isDiscordLinked(con, discordId);
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isMinecraftLinked(UUID mcUuid) {
        if (!isEnabled()) return false;
        try (Connection con = db.getConnection()) {
            return isMinecraftLinked(con, mcUuid);
        } catch (SQLException e) {
            return false;
        }
    }

    public Optional<UUID> getMinecraftUuidForDiscord(String discordId) {
        if (!isEnabled()) return Optional.empty();
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT mc_uuid FROM account_links WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of((UUID) rs.getObject(1));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public Optional<String> getDiscordIdForMinecraft(UUID mcUuid) {
        if (!isEnabled()) return Optional.empty();
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT discord_id FROM account_links WHERE mc_uuid = ?")) {
            ps.setObject(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private boolean isDiscordLinked(Connection con, String discordId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM account_links WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isMinecraftLinked(Connection con, UUID mcUuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM account_links WHERE mc_uuid = ?")) {
            ps.setObject(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int cleanupExpiredCodes() {
        if (!isEnabled()) return 0;
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM link_codes WHERE redeemed = FALSE AND expires_at < CURRENT_TIMESTAMP")) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    private int safeInt(ConfigKey key, int def) {
        try { return config.get(key).asInt(); } catch (Throwable ignored) { return def; }
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private boolean isUniqueViolation(SQLException ex) {
        // PostgreSQL unique_violation SQLSTATE 23505
        return "23505".equals(ex.getSQLState());
    }

    public UnlinkResult unlinkMinecraft(UUID mcUuid) {
        if (!isEnabled()) return UnlinkResult.DB_ERROR;
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM account_links WHERE mc_uuid = ?")) {
            ps.setObject(1, mcUuid);
            int updated = ps.executeUpdate();
            if (updated == 0) return UnlinkResult.NOT_LINKED;
            return UnlinkResult.SUCCESS;
        } catch (SQLException e) {
            return UnlinkResult.DB_ERROR;
        }
    }

    public UnlinkResult unlinkDiscord(String discordId) {
        if (!isEnabled()) return UnlinkResult.DB_ERROR;
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM account_links WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            int updated = ps.executeUpdate();
            if (updated == 0) return UnlinkResult.NOT_LINKED;
            return UnlinkResult.SUCCESS;
        } catch (SQLException e) {
            return UnlinkResult.DB_ERROR;
        }
    }
}
