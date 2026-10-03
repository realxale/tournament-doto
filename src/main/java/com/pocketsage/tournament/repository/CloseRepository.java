package com.pocketsage.tournament.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Клозы — серии матчей.
 *
 * <p>Номер клоза выдаёт последовательность close_number_seq (миграция V3),
 * время старта проставляется на сервере БД. Статусы повторяют CHECK из V1.
 */
public class CloseRepository {

    /**
     * Порядок колонок и параметров обязан совпадать: статус тоже передаётся
     * параметром, иначе литерал в VALUES сдвинет нумерацию и в boolean-колонку
     * попадёт строка.
     */
    private static final String INSERT_SQL = """
           INSERT INTO close (
               owner_id, status, discord_category_id,
               immortal_draft, gamemode, toxic
           )
           VALUES (?, ?, ?, ?, ?, ?)
           RETURNING id, number
        """;

    private static final String STATUS_OPEN = "OPEN";

    private static final String CANCEL_SQL = """
           UPDATE close SET status = 'CANCELLED', time_end = now() WHERE id = ?
        """;

    private static final String FINISH_SQL = """
           UPDATE close SET status = 'FINISHED', time_end = now() WHERE id = ?
        """;

    private static final String DELETE_SQL = "DELETE FROM close WHERE id = ?";

    private static final String SELECT_BY_CATEGORY_SQL = """
           SELECT id, number, owner_id, status
             FROM close
            WHERE discord_category_id = ?
        """;

    private final Database db;

    public CloseRepository() {
        this(Database.getInstance());
    }

    /** Конструктор с явной базой — нужен тестам. */
    public CloseRepository(Database db) {
        this.db = db;
    }

    /** Создаёт клоз в статусе OPEN. Возвращает id и номер клоза. */
    public Created create(
        long ownerPlayerId,
        String discordCategoryId,
        String gamemode,
        boolean immortalDraft,
        boolean toxic
    ) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(INSERT_SQL)
        ) {
            ps.setLong(1, ownerPlayerId);
            ps.setString(2, STATUS_OPEN);
            ps.setString(3, discordCategoryId);
            ps.setBoolean(4, immortalDraft);
            ps.setString(5, gamemode);
            ps.setBoolean(6, toxic);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Created(rs.getLong("id"), rs.getInt("number"));
                }
            }
            throw new SQLException("БД не вернула созданный клоз");
        }
    }

    /** Клоз удалён: время конца и статус CANCELLED, запись остаётся в БД. */
    public void cancel(long closeId) throws SQLException {
        updateStatus(CANCEL_SQL, closeId);
    }

    /** Клоз доигран: время конца и статус FINISHED. */
    public void finish(long closeId) throws SQLException {
        updateStatus(FINISH_SQL, closeId);
    }

    /** Полное удаление клоза вместе с матчами и составами (ON DELETE CASCADE). */
    public int delete(long closeId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(DELETE_SQL)
        ) {
            ps.setLong(1, closeId);
            return ps.executeUpdate();
        }
    }

    /** Ищет клоз по id его категории в Discord. */
    public Optional<Created> findByDiscordCategory(String discordCategoryId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_BY_CATEGORY_SQL)
        ) {
            ps.setString(1, discordCategoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                    new Created(rs.getLong("id"), rs.getInt("number"))
                );
            }
        }
    }

    private void updateStatus(String sql, long closeId) throws SQLException {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, closeId);
            ps.executeUpdate();
        }
    }

    /** Идентификаторы созданного клоза. */
    public record Created(long id, int number) {}
}