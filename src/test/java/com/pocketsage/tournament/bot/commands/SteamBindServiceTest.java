package com.pocketsage.tournament.bot.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pocketsage.tournament.model.Player;
import com.pocketsage.tournament.repository.Database;
import com.pocketsage.tournament.repository.PlayerRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Интеграционный тест привязки Steam-аккаунта к Discord-профилю.
 *
 * <p>Нужна пустая PostgreSQL-база, переданная через переменные окружения
 * TEST_DATABASE_URL / TEST_DATABASE_LOGIN / TEST_DATABASE_PASSWORD.
 * Если TEST_DATABASE_URL не задан — тест пропускается.
 */
class SteamBindServiceTest {

    private static Database database;
    private static PlayerRepository players;

    private long discordId;

    @BeforeAll
    static void prepareDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        Assumptions.assumeTrue(
            url != null && !url.isBlank(),
            "TEST_DATABASE_URL не задан — интеграционный тест пропущен"
        );

        String login = System.getenv().getOrDefault("TEST_DATABASE_LOGIN", "postgres");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");

        // заодно проверяем, что миграции V1/V2 накатываются на пустую базу
        Flyway.configure()
            .dataSource(url, login, password)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        database = new Database(url, login, password);
        players = new PlayerRepository(database);
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void newDiscordId() {
        discordId = ThreadLocalRandom.current()
            .nextLong(1_000_000_000_000_000L, 9_007_199_254_740_991L);
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (players != null) {
            players.delete(new Player(discordId, "tester", "tester"));
        }
    }

    @Test
    void firstBindCreatesPlayerInDatabase() throws SQLException {
        SteamBindService.BindResult result = service().bind(discordId, "tester", "Suma1L");

        assertTrue(result.created(), "первый bind должен создать игрока");
        assertNotNull(result.player().getId(), "id должен вернуться из БД");
        assertEquals("Suma1L", result.player().getSteamName());

        Optional<Player> saved = players.findByDiscordId(discordId);
        assertTrue(saved.isPresent(), "игрок должен лежать в players");
        assertEquals("tester", saved.get().getName(), "в name пишем Discord-имя");
        assertEquals("Suma1L", saved.get().getSteamName());
        assertEquals(0L, saved.get().getWinPoints(), "win_points по умолчанию 0");
    }

    @Test
    void secondBindUpdatesSteamNameWithoutDuplicatingPlayer() throws SQLException {
        service().bind(discordId, "tester", "OldName");

        SteamBindService.BindResult second = service().bind(discordId, "tester", "NewName");

        assertFalse(second.created(), "повторный bind не создаёт дубль");
        assertEquals("NewName", second.player().getSteamName());
        assertEquals(1L, countPlayers(discordId), "в players должна быть одна строка");
    }

    @Test
    void blankDiscordNameFallsBackToSteamName() throws SQLException {
        SteamBindService.BindResult result = service().bind(discordId, "   ", "Suma1L");

        assertEquals("Suma1L", result.player().getName(), "name NOT NULL — берём steam_name");
    }

    private SteamBindService service() {
        return new SteamBindService(players);
    }

    private static long countPlayers(long discordId) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM players WHERE discord_id = ?"
             )) {
            ps.setLong(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
