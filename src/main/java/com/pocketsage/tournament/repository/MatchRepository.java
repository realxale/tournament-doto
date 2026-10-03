package com.pocketsage.tournament.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.sql.Statement;

/**
 * Матчи внутри клоза.
 *
 * <p>Клоз — серия матчей; у каждого матча свой состав (players_match) и свои
 * времена начала/конца. Статусы повторяют CHECK из миграции V3:
 * {@code COLLECTING → FINISHED | CANCELLED}.
 */
public class MatchRepository {

    private static final String INSERT_SQL = """
           INSERT INTO match (close_id, number, status, gamemode, immortal_draft, toxic, time_start)
           VALUES (?, ?, 'COLLECTING', ?, ?, ?, now())
           RETURNING id
        """;

    /** Состав перезаписывается на каждом изменении — id игрока в пределах матча уникален. */
    private static final String UPSERT_PARTICIPANT_SQL = """
           INSERT INTO players_match (match_id, player_id, team)
           VALUES (?, ?, ?)
           ON CONFLICT (match_id, player_id)
           DO UPDATE SET team = EXCLUDED.team
        """;

    /** Сохраняем ник только при первом входе в матч (nickname_saved = FALSE). */
    private static final String REMEMBER_NICKNAME_SQL = """
           UPDATE players_match
              SET original_nickname = ?, nickname_saved = TRUE
            WHERE match_id = ? AND player_id = ? AND nickname_saved = FALSE
        """;

    private static final String SELECT_NICKNAME_SQL = """
           SELECT original_nickname
             FROM players_match
            WHERE match_id = ? AND player_id = ? AND nickname_saved = TRUE
        """;

    private static final String CLEAR_NICKNAMES_SQL = """
           UPDATE players_match
              SET original_nickname = NULL, nickname_saved = FALSE
            WHERE match_id = ?
        """;

    private static final String DELETE_PARTICIPANT_SQL = """
           DELETE FROM players_match WHERE match_id = ? AND player_id = ?
        """;

    private static final String FINISH_SQL = """
           UPDATE match
              SET time_end = now(), status = 'FINISHED', result = ?
            WHERE id = ?
        """;

    private static final String CANCEL_SQL = """
           UPDATE match SET time_end = now(), status = 'CANCELLED' WHERE id = ?
        """;

    private static final String DELETE_SQL = "DELETE FROM match WHERE id = ?";

    private final Database db;

    public MatchRepository() {
        this(Database.getInstance());
    }

    /** Конструктор с явной базой — нужен тестам. */
    public MatchRepository(Database db) {
        this.db = db;
    }

    /** Создаёт матч в статусе COLLECTING. Возвращает id нового матча. */
    public long create(
        long closeId,
        int number,
        String gamemode,
        boolean immortalDraft,
        boolean toxic
    ) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                INSERT_SQL,
                Statement.RETURN_GENERATED_KEYS
            )
        ) {
            ps.setLong(1, closeId);
            ps.setInt(2, number);
            ps.setString(3, gamemode);
            ps.setBoolean(4, immortalDraft);
            ps.setBoolean(5, toxic);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("БД не вернула id созданного матча");
        }
    }

    /** Добавляет игрока в состав матча (или меняет его команду). */
    public void addParticipant(long matchId, long playerId, char team) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(UPSERT_PARTICIPANT_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.setLong(2, playerId);
            ps.setString(3, String.valueOf(team));
            ps.executeUpdate();
        }
    }

    /**
     * Сохраняет ник участника ДО подмены на Steam-ник.
     *
     * <p>Вызывается один раз при старте игры. Значение нужно, чтобы после
     * матча вернуть ник как было — сам применённый Steam-ник восстанавливать
     * не из чего. Если ник уже сохранён, повторный вызов его не перетирает:
     * иначе второй заход подменил бы ник поверх подмены.
     */
    public void rememberOriginalNickname(
        long matchId,
        long playerId,
        String nickname
    ) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(REMEMBER_NICKNAME_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.setLong(2, playerId);
            if (nickname == null || nickname.isBlank()) {
                // null ≠ пустая строка: пустой ник сбросит ник на глобальный
                ps.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, nickname);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Сохранённый ник участника матча.
     *
     * <p>{@link Optional#empty()} — ник не меняли, откатывать нечего.
     * Внутри может быть {@code null}: это значит, что у игрока не было
     * ника на сервере, и вернуть надо глобальное имя ({@code null}).
     */
    public Optional<String> findOriginalNickname(long matchId, long playerId)
        throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_NICKNAME_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                // ofNullable, а не of: исходный ник может быть законно null
                return rs.next()
                    ? Optional.ofNullable(rs.getString("original_nickname"))
                    : Optional.empty();
            }
        }
    }

    /** Больше нечего возвращать — чистим сохранённые ники матча. */
    public void clearOriginalNicknames(long matchId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(CLEAR_NICKNAMES_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.executeUpdate();
        }
    }

    /** Убирает игрока из состава матча. */
    public void removeParticipant(long matchId, long playerId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(DELETE_PARTICIPANT_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * Матч доигран: пишем время конца и статус FINISHED.
     *
     * @param winner 'a', 'b' или null — победитель ещё не известен
     */
    public void finish(long matchId, Character winner) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(FINISH_SQL)
        ) {
            if (winner == null) {
                ps.setNull(1, java.sql.Types.CHAR);
            } else {
                ps.setString(1, String.valueOf(winner.charValue()));
            }
            ps.setLong(2, matchId);
            ps.executeUpdate();
        }
    }

    /** Матч не состоялся: время конца и статус CANCELLED, запись остаётся в БД. */
    public void cancel(long matchId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(CANCEL_SQL)
        ) {
            ps.setLong(1, matchId);
            ps.executeUpdate();
        }
    }

    /** Полное удаление матча вместе с составом (ON DELETE CASCADE). */
    public int delete(long matchId) throws SQLException {
        try (
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(DELETE_SQL)
        ) {
            ps.setLong(1, matchId);
            return ps.executeUpdate();
        }
    }
}