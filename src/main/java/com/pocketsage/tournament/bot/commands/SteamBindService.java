package com.pocketsage.tournament.bot.commands;

import com.pocketsage.tournament.model.Player;
import com.pocketsage.tournament.repository.PlayerRepository;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Привязка Steam-аккаунта к Discord-профилю (/bind).
 *
 * <p>Первый bind создаёт игрока в таблице players, повторный — обновляет
 * steam_name, чтобы одному Discord-аккаунту всегда соответствовала одна строка.
 */
public class SteamBindService {

    private final PlayerRepository players;

    public SteamBindService() {
        this(new PlayerRepository());
    }

    /** Конструктор с готовым репозиторием — нужен тестам. */
    public SteamBindService(PlayerRepository players) {
        this.players = players;
    }

    /**
     * Сохраняет имя Steam-аккаунта за Discord-пользователем.
     *
     * @param discordId   Discord id пользователя (players.discord_id)
     * @param discordName отображаемое имя в Discord (players.name)
     * @param steamName   имя Steam-аккаунта из модалки (players.steam_name)
     * @return сохранённый игрок и признак того, что запись создана впервые
     */
    public BindResult bind(long discordId, String discordName, String steamName) throws SQLException {
        Optional<Player> existing = players.findByDiscordId(discordId);

        if (existing.isEmpty()) {
            Player toSave = new Player(
                discordId,
                nameOrDefault(discordName, steamName),
                steamName
            );
            return new BindResult(players.save(toSave), true);
        }

        Player player = existing.get();
        player.setSteamName(steamName);
        players.update(player);
        return new BindResult(player, false);
    }

    /** players.name — NOT NULL, поэтому при пустом Discord-имени берём steam_name. */
    private static String nameOrDefault(String discordName, String steamName) {
        if (discordName == null || discordName.isBlank()) {
            return steamName;
        }
        return discordName;
    }

    /** Результат привязки: игрок + был ли он создан впервые. */
    public record BindResult(Player player, boolean created) {}
}
