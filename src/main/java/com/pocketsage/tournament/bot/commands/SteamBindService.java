package com.pocketsage.tournament.bot.commands;

import com.pocketsage.tournament.bot.commands.OpenDotaClient.SteamProfile;
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

    /**
     * Сохраняет профиль Steam-аккаунта за Discord-пользователем.
     *
     * <p>Данные приходят из OpenDota ({@link OpenDotaClient}), а не из формы:
     * игрок вводит только Steam ID, а ник, ранг и MMR подставляет бот. Поэтому
     * здесь нет валидации длины — она уже прошла при разборе id.
     *
     * <p>Один Discord-аккаунт — одна строка {@code players}: повторный вызов
     * обновляет существующую запись, а не плодит дубли. А вот обратное верно:
     * {@code steam_id} в БД {@code UNIQUE}, поэтому один Steam-аккаунт не может
     * принадлежать двум разным людям — конфликт честно пробрасывается вызывающему.
     *
     * @param discordId   Discord id пользователя
     * @param discordName отображаемое имя в Discord (players.name)
     * @param profile     профиль из OpenDota
     * @return сохранённый игрок и признак того, что запись создана впервые
     */
    public BindResult bindProfile(long discordId, String discordName, SteamProfile profile)
        throws SQLException {
        String steamName = profile.steamName();
        Optional<Player> existing = players.findByDiscordId(discordId);

        if (existing.isEmpty()) {
            Player toSave = new Player(
                discordId,
                profile.steamId64(),
                // record отдаёт примитив long, а в БД колонка BIGINT —
                // Long нужен, потому что поле nullable
                Long.valueOf(profile.accountId()),
                nameOrDefault(discordName, steamName),
                steamName,
                profile.mmr(),
                // очки не начисляет никто — берём значение по умолчанию из схемы
                0L
            );
            return new BindResult(players.save(toSave), true);
        }

        Player player = existing.get();
        player.setSteamId(profile.steamId64());
        player.setDotaAccountId(Long.valueOf(profile.accountId()));
        player.setSteamName(steamName);
        if (profile.mmr() != null) {
            player.setMmr(profile.mmr());
        }
        players.update(player);
        return new BindResult(player, false);
    }

    /** players.name — NOT NULL, поэтому при пустом Discord-имени берём steam_name. */
    private static String nameOrDefault(String discordName, String steamName) {
        if (steamName == null || steamName.isBlank()) {
            return discordName == null || discordName.isBlank()
                ? "player"
                : discordName;
        }
        if (discordName == null || discordName.isBlank()) {
            return steamName;
        }
        return discordName;
    }

    /** Результат привязки: игрок + был ли он создан впервые. */
    public record BindResult(Player player, boolean created) {}
}
