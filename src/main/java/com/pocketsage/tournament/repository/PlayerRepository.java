package com.pocketsage.tournament.repository;

import com.pocketsage.tournament.model.Player;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Игроки — CRUD над таблицей {@code players}.
 *
 * <p>Идентификатор всегда {@code discord_id}, а не суррогатный {@code id}: Discord
 * приходит в бота событием, у нас есть только id пользователя, и искать по нему
 * приходится чаще всего.
 */
public class PlayerRepository {

    private static final String INSERT_SQL = """
           INSERT INTO players (discord_id, steam_id, dota_account_id, name, steam_name, mmr, win_points)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           """;

    private static final String SELECT_BY_DISCORD_ID_SQL = """
           SELECT id, discord_id, steam_id, dota_account_id, name, steam_name, mmr, win_points
             FROM players
            WHERE discord_id = ?
           """;

    private static final String UPDATE_SQL = """
           UPDATE players
              SET steam_id = ?, dota_account_id = ?, name = ?, steam_name = ?, mmr = ?, updated_at = now()
            WHERE discord_id = ?
           """;

    private static final String DELETE_SQL = """
           DELETE FROM players WHERE discord_id = ?
           """;

    private final Database db;

    public PlayerRepository() {
        this(Database.getInstance());
    }

    /** Конструктор с явной базой — нужен тестам. */
    public PlayerRepository(Database db) {
        this.db = db;
    }

    /** INSERT нового игрока. Возвращает игрока с проставленным id. */
    public Player save(Player player) throws SQLException {
        try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                INSERT_SQL,
                Statement.RETURN_GENERATED_KEYS
            )) {
            ps.setLong(1, player.getDiscordId());
            ps.setObject(2, player.getSteamId()); // Long → может быть null
            ps.setObject(3, player.getDotaAccountId());
            ps.setString(4, player.getName());
            ps.setString(5, player.getSteamName()); // может быть null
            ps.setObject(6, player.getMmr()); // Integer → может быть null
            ps.setLong(7, player.getWinPoints());
            ps.executeUpdate();

            // 3. Получаем сгенерированный id
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    player.setId(keys.getLong(1));
                }
            }
            return player;
        }
    }

    /**
     * Возвращает id игрока по Discord-аккаунту, создавая строку при первом
     * обращении.
     *
     * <p>Нужен там, где на игрока ссылаются другие таблицы (close.owner_id,
     * players_match.player_id): Discord-аккаунт мог не выполнить /bind.
     */
    public long ensureId(long discordId, String name) throws SQLException {
        Optional<Player> existing = findByDiscordId(discordId);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        String safeName =
            name == null || name.isBlank() ? "player#" + discordId : name;
        return save(new Player(discordId, safeName, null)).getId();
    }

    /** Ищет игрока по Discord id. */
    public Optional<Player> findByDiscordId(long discordId) throws SQLException {
        try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_BY_DISCORD_ID_SQL)) {
            ps.setLong(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    /** UPDATE по discord_id. false — если такого игрока в БД нет. */
    public boolean update(Player player) throws SQLException {
        try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(UPDATE_SQL)) {
            ps.setObject(1, player.getSteamId());
            ps.setObject(2, player.getDotaAccountId());
            ps.setString(3, player.getName());
            ps.setString(4, player.getSteamName());
            ps.setObject(5, player.getMmr());
            ps.setLong(6, player.getDiscordId());
            int rows = ps.executeUpdate();
            return rows > 0;
        }
    }

    /** DELETE по discord_id. Возвращает количество удалённых строк. */
    public int delete(Player player) throws SQLException {
        try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
            ps.setLong(1, player.getDiscordId());
            return ps.executeUpdate();
        }
    }

    private static Player mapRow(ResultSet rs) throws SQLException {
        Player player = new Player(
            rs.getLong("discord_id"),
            rs.getObject("steam_id", Long.class),
            rs.getObject("dota_account_id", Long.class),
            rs.getString("name"),
            rs.getString("steam_name"),
            rs.getObject("mmr", Integer.class),
            rs.getLong("win_points")
        );
        player.setId(rs.getLong("id"));
        return player;
    }
}
