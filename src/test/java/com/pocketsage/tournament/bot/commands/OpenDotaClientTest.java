package com.pocketsage.tournament.bot.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketsage.tournament.bot.commands.OpenDotaClient.SteamProfile;
import com.pocketsage.tournament.bot.commands.OpenDotaClient.SteamProfileException;
import org.junit.jupiter.api.Test;

/**
 * Разбор Steam ID и разбор ответа OpenDota.
 *
 * <p>Сеть не используется: {@code parseAccountId} и {@code mapProfile} — чистые
 * функции, поэтому их проверки не зависят от доступности внешнего API и не
 * тормозят сборку. Реальный поход в OpenDota проверяется только вручную.
 */
class OpenDotaClientTest {

    /** Настоящий аккаунт Dendi: SteamID64, account_id и ник связаны этой формулой. */
    private static final long DENDI_ACCOUNT_ID = 70388657L;
    private static final long DENDI_STEAM_ID64 = 76561198030654385L;

    private final ObjectMapper mapper = new ObjectMapper();

    // ===== Разбор ввода =====

    @Test
    void plainAccountIdIsTakenAsIs() {
        assertEquals(DENDI_ACCOUNT_ID, OpenDotaClient.parseAccountId("70388657"));
    }

    @Test
    void steamId64IsConvertedToAccountId() {
        assertEquals(
            DENDI_ACCOUNT_ID,
            OpenDotaClient.parseAccountId("76561198030654385"),
            "SteamID64 = account_id + база"
        );
    }

    @Test
    void classicSteam2IdIsConverted() {
        // STEAM_0:Y:Z → account_id = Z*2 + Y, поэтому Z = (70388657 - 1) / 2
        assertEquals(DENDI_ACCOUNT_ID, OpenDotaClient.parseAccountId("STEAM_0:1:35194328"));
        // регистр префикса не важен — так пишут и в ссылках, и в буфере обмена
        assertEquals(DENDI_ACCOUNT_ID, OpenDotaClient.parseAccountId("steam_0:1:35194328"));
    }

    @Test
    void whitespaceAroundIdIsIgnored() {
        assertEquals(DENDI_ACCOUNT_ID, OpenDotaClient.parseAccountId("  70388657\n"));
    }

    @Test
    void profileUrlWithNumericTailIsAccepted() {
        assertEquals(
            DENDI_ACCOUNT_ID,
            OpenDotaClient.parseAccountId("https://steamcommunity.com/profiles/70388657/"),
            "из ссылки берём последний числовой сегмент"
        );
    }

    @Test
    void vanityUrlIsRejectedWithHint() {
        // OpenDota не умеет разворачивать vanity-имена в account_id
        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class,
            () -> OpenDotaClient.parseAccountId("https://steamcommunity.com/id/dendi")
        );
        assertTrue(e.getMessage().contains("числовой"), "в тексте должно быть слово про цифры");
    }

    @Test
    void emptyInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OpenDotaClient.parseAccountId(""));
        assertThrows(IllegalArgumentException.class, () -> OpenDotaClient.parseAccountId("   "));
        assertThrows(IllegalArgumentException.class, () -> OpenDotaClient.parseAccountId(null));
    }

    @Test
    void nonNumericInputIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenDotaClient.parseAccountId("Suma1L")
        );
    }

    @Test
    void brokenClassicIdIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenDotaClient.parseAccountId("STEAM_0:1")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenDotaClient.parseAccountId("STEAM_0:x:y")
        );
    }

    @Test
    void overflowingNumberIsRejectedInsteadOfWrapping() {
        // без try/catch Long.parseLong бросил бы NumberFormatException
        // на цифрах, а не «нормальное» отрицательное число
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenDotaClient.parseAccountId("99999999999999999999999")
        );
    }
// ===== Разбор ответа =====

    /** Реальный фрагмент ответа OpenDota для профиля с матчами. */
    private static final String PLAYER_WITH_DATA = """
        {
          "profile": {
            "account_id": 70388657,
            "personaname": "hidden pool player",
            "name": "Dendi",
            "steamid": "76561198030654385",
            "avatarfull": "https://avatars.steamstatic.com/example.jpg"
          },
          "rank_tier": 80,
          "computed_mmr": 4370.13
        }
        """;

    @Test
    void profileWithDataIsFullyMapped() throws Exception {
        SteamProfile profile = OpenDotaClient.mapProfile(
            mapper.readTree(PLAYER_WITH_DATA), DENDI_ACCOUNT_ID);

        assertEquals(DENDI_ACCOUNT_ID, profile.accountId());
        assertEquals(DENDI_STEAM_ID64, profile.steamId64());
        assertEquals("Dendi", profile.steamName(), "name предпочтительнее personaname");
        assertEquals(80, profile.rankTier());
        assertEquals(4370, profile.mmr(), "MMR округляется до целого");
        assertTrue(profile.hasSteamName());
    }

    @Test
    void fallsBackToPersonanameWhenNameIsAbsent() throws Exception {
        SteamProfile profile = OpenDotaClient.mapProfile(
            mapper.readTree("""
                {"profile": {"account_id": 70388657, "personaname": "Suma1L",
                             "steamid": "76561198030654385"}}
                """),
            DENDI_ACCOUNT_ID);

        assertEquals("Suma1L", profile.steamName());
    }

    @Test
    void privateProfileKeepsAccountIdButHasNoSteamData() throws Exception {
        // профиль скрыт настройками приватности: profile отсутствует целиком
        SteamProfile profile = OpenDotaClient.mapProfile(
            mapper.readTree("{}"), DENDI_ACCOUNT_ID);

        assertEquals(DENDI_ACCOUNT_ID, profile.accountId(), "id известен");
        assertNull(profile.steamId64());
        assertNull(profile.steamName());
        assertNull(profile.mmr());
        assertNull(profile.rankTier());
        assertFalse(profile.hasSteamName(), "подменять нечего — это сигнал для /bind_old");
    }

    @Test
    void errorResponseIsReportedAsMissingProfile() throws Exception {
        assertThrows(
            SteamProfileException.class,
            () -> OpenDotaClient.mapProfile(
                mapper.readTree("{\"error\":\"Not Found\"}"), DENDI_ACCOUNT_ID)
        );
    }

    @Test
    void nullResponseIsReportedAsMissingProfile() {
        assertThrows(
            SteamProfileException.class,
            () -> OpenDotaClient.mapProfile(null, DENDI_ACCOUNT_ID)
        );
    }

    @Test
    void mmrIsNullWhenOpenDotaDoesNotKnowIt() throws Exception {
        SteamProfile profile = OpenDotaClient.mapProfile(
            mapper.readTree("""
                {"profile": {"account_id": 70388657, "name": "Novice",
                             "steamid": "76561198030654385"}}
                """),
            DENDI_ACCOUNT_ID);

        assertEquals("Novice", profile.steamName());
        assertNull(profile.mmr(), "нет данных о MMR — поле остаётся пустым");
        assertNull(profile.rankTier());
    }
}