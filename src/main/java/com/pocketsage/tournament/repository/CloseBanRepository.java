package com.pocketsage.tournament.repository;

import com.pocketsage.tournament.model.CloseBan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Таблица обвиняемых — CRUD над {@code close_ban}.
 *
 * <p>Банается Discord id напрямую, а не {@code players.id}: обвиняемый мог ни
 * разу не выполнить {@code /bind}, и бан всё равно нужно уметь выдать.
 *
 * <p>Таблица и есть источник правды: бан переживает перезапуск бота, а по
 * истечении срока перестаёт действовать сам — {@code time_end} сравнивается с
 * {@code now()} прямо в запросе, без очистки по таймеру.
 */
public class CloseBanRepository {

    /**
     * Порядок колонок и параметров обязан совпадать: срок передаётся параметром,
     * иначе литерал в VALUES сдвинет нумерацию (см. грабли в ARCHITECTURE.md).
     */
    private static final String INSERT_SQL = """
            INSERT INTO close_ban (discord_id, ban_reason, time_end)
            VALUES (?, ?, ?)
            RETURNING created_at
        """;

    private static final String SELECT_ACTIVE_SQL = """
            SELECT * FROM close_ban
             WHERE discord_id = ?
               AND lifted_at IS NULL
               AND (time_end IS NULL OR time_end > now())
             ORDER BY created_at DESC
             LIMIT 1
        """;

    private static final String SELECT_HISTORY_SQL = """
            SELECT * FROM close_ban
             WHERE discord_id = ?
             ORDER BY created_at DESC
        """;

    private static final String LIFT_SQL = """
            UPDATE close_ban
               SET lifted_at = now()
             WHERE discord_id = ?
               AND lifted_at IS NULL
               AND (time_end IS NULL OR time_end > now())
        """;

    private final Database db;

    public CloseBanRepository() {
        this(Database.getInstance());
    }

    /** Конструктор с явной базой — нужен тестам. */
    public CloseBanRepository(Database db) {
        this.db = db;
    }

    /**
     * Выдаёт бан.
     *
     * @param discordId     к кому применён
     * @param reason        причина
     * @param durationHours срок в часах; {@code 0} или меньше = навсегда
     */
    public CloseBan issue(long discordId, String reason, int durationHours) throws SQLException {
        // срок хранится только как time_end; 0 и отрицательные — навсегда
        Instant timeEnd = durationHours > 0
            ? Instant.now().plusSeconds(durationHours * 3600L)
            : null;

        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(INSERT_SQL)
        ) {
            ps.setLong(1, discordId);
            ps.setString(2, reason);
            if (timeEnd == null) {
                // тип указываем явно: setNull без типа принимают не все драйверы
                ps.setNull(3, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(3, Timestamp.from(timeEnd));
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("БД не вернула созданный бан");
                }
                return new CloseBan(
                    discordId,
                    reason,
                    rs.getTimestamp("created_at").toInstant(),
                    timeEnd,
                    null
                );
            }
        }
    }

    /** Действующий бан или пусто — главная проверка перед записью. */
    public Optional<CloseBan> findActive(long discordId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_SQL)
        ) {
            ps.setLong(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Вся история банов игрока, свежие сверху — для /close_ban_show. */
    public List<CloseBan> findHistory(long discordId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_HISTORY_SQL)
        ) {
            ps.setLong(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CloseBan> bans = new ArrayList<>();
                while (rs.next()) {
                    bans.add(mapRow(rs));
                }
                return bans;
            }
        }
    }

    /**
     * Снимает действующий бан.
     *
     * <p>Истёкшие не трогаются: они уже не мешают, а история должна остаться
     * нетронутой.
     *
     * @return 1 — бан был и снят; 0 — бана не было
     */
    public int lift(long discordId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(LIFT_SQL)
        ) {
            ps.setLong(1, discordId);
            return ps.executeUpdate();
        }
    }

    private static CloseBan mapRow(ResultSet rs) throws SQLException {
        Timestamp timeEnd = rs.getTimestamp("time_end");
        Timestamp liftedAt = rs.getTimestamp("lifted_at");
        return new CloseBan(
            rs.getLong("discord_id"),
            rs.getString("ban_reason"),
            rs.getTimestamp("created_at").toInstant(),
            timeEnd == null ? null : timeEnd.toInstant(),
            liftedAt == null ? null : liftedAt.toInstant()
        );
    }
}
