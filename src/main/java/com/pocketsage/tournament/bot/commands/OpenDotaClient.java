package com.pocketsage.tournament.bot.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Клиент OpenDota API: разрезолвит идентификатор Steam-аккаунта в профиль.
 *
 * <p>Работает и без API-ключа (OpenDota отдаёт данные анонимно), ключ из
 * {@code OPENDOTA_APIKEY} подставляется, только если он задан.
 *
 * <p>На вход принимается свободный текст: игрок вставляет то, что нашёл — ID
 * из клиента Dota, SteamID64, {@code STEAM_0:1:2} или ссылку на профиль.
 * Правила разбора сосредоточены в {@link #parseAccountId(String)}, поэтому
 * их легко покрыть тестами без обращения к сети.
 */
public class OpenDotaClient {

    private static final String BASE = "https://api.opendota.com/api";
    private static final int TIMEOUT_SECONDS = 10;

    /**
     * Разница между SteamID64 и Dota {@code account_id}:
     * {@code steamid64 = account_id + 76561197960265728}.
     */
    static final long STEAM_ID64_BASE = 76561197960265728L;

    /** Верхняя граница разумного account_id: SteamID64 всегда больше неё. */
    private static final long ACCOUNT_ID_CEILING = STEAM_ID64_BASE;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** Непустой ключ подставляется в query, иначе запрос идёт без ключа. */
    private final String apiKey;

    public OpenDotaClient() {
        this(null);
    }

    public OpenDotaClient(String apiKey) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    /**
     * Возвращает профиль по строке, введённой игроком.
     *
     * @throws IllegalArgumentException строка не похожа на Steam-идентификатор
     * @throws SteamProfileException    такого аккаунта нет
     * @throws IOException              сеть недоступна или ответ не разобран
     */
    public SteamProfile resolve(String rawInput) throws IOException {
        long accountId = parseAccountId(rawInput);

        String url = BASE + "/players/" + accountId;
        if (apiKey != null) {
            url = url + "?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = send(request);

        if (response.statusCode() == 404) {
            throw new SteamProfileException(
                "Аккаунт с таким ID не найден в Dota 2. "
                    + "Проверь, что это ID именно из Dota, а не из Steam Community.");
        }
        if (response.statusCode() == 429) {
            throw new SteamProfileException(
                "OpenDota временно не отвечает — попробуй через минуту.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OpenDota ответила кодом " + response.statusCode());
        }

        return mapProfile(mapper.readTree(response.body()), accountId);
    }

    /**
     * Обёртка над {@code http.send}: превращает {@link InterruptedException}
     * в IOException, сохраняя флаг прерывания.
     *
     * <p>Так сигнатура {@link #resolve} остаётся без checked-исключения,
     * о котором не знает вызывающий, но поток при этом не теряет прерывание —
     * иначе отмена задачи JDA перестала бы работать.
     */
    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Прерван запрос к OpenDota", e);
        }
    }

    /**
     * Приводит строку к Dota {@code account_id}.
     *
     * <p>Поддерживаются {@code account_id} (70388657), {@code SteamID64}
     * (76561198030654385), {@code STEAM_0:1:35194328} и ссылка на профиль
     * с числовым хвостом. Vanity-имя ({@code steamcommunity.com/id/dendi})
     * OpenDota не разрезолвит, поэтому на него выбрасывается понятная ошибка
     * вместо выдуманного account_id.
     */
    static long parseAccountId(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("Пустой Steam ID.");
        }
        String value = rawInput.trim();

        // ссылка: забираем последний непустой сегмент пути
        if (value.contains("/")) {
            String[] segments = value.split("/");
            String last = segments[segments.length - 1].trim();
            if (!isDigits(last)) {
                throw new IllegalArgumentException(
                    "Кастомная ссылка Steam (steamcommunity.com/id/…) не подходит. "
                        + "Нужен числовой ID — он виден в клиенте Dota.");
            }
            value = last;
        }

        // классический STEAM_0:Y:Z
        if (value.regionMatches(true, 0, "STEAM_", 0, 6)) {
            String[] parts = value.split(":");
            if (parts.length != 3 || !isDigits(parts[1]) || !isDigits(parts[2])) {
                throw new IllegalArgumentException("Не похоже на STEAM_0:Y:Z.");
            }
            long accountId = Long.parseLong(parts[2]) * 2L + Long.parseLong(parts[1]);
            return requireInRange(accountId);
        }

        // голые числа: SteamID64 приводим к account_id
        if (!isDigits(value)) {
            throw new IllegalArgumentException("Steam ID должен состоять из цифр.");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Steam ID слишком длинный.");
        }
        return parsed >= STEAM_ID64_BASE
            ? requireInRange(parsed - STEAM_ID64_BASE)
            : requireInRange(parsed);
    }

    private static long requireInRange(long accountId) {
        if (accountId < 0 || accountId >= ACCOUNT_ID_CEILING) {
            throw new IllegalArgumentException("Steam ID вне допустимого диапазона.");
        }
        return accountId;
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Разбирает ответ OpenDota.
     *
     * <p>Схема ответа неоднородна: профиль лежит в объекте {@code profile},
     * а {@code rank_tier} и {@code computed_mmr} — на верхнем уровне. Для закрытых
     * профилей верхнего уровня может не быть вовсе, поэтому всё читается через
     * хелперы, переживающие отсутствие узла.
     */
    static SteamProfile mapProfile(JsonNode root, long accountId) throws SteamProfileException {
        if (root == null || !root.isObject() || root.has("error")) {
            throw new SteamProfileException("Аккаунт с таким ID не найден в Dota 2.");
        }

        JsonNode profile = root.path("profile");
        if (profile.isMissingNode() || profile.isNull()) {
            // профиль закрыт настройками приватности: ID валиден, данных Steam нет
            return new SteamProfile(accountId, null, null, null, null);
        }

        String steamName = text(profile, "name");
        if (steamName == null) {
            steamName = text(profile, "personaname");
        }

        Long steamId64 = null;
        String steamid = text(profile, "steamid");
        if (isDigits(steamid)) {
            steamId64 = Long.parseLong(steamid);
        }

        Double mmr = decimal(root, "computed_mmr");

        return new SteamProfile(
            asLong(text(profile, "account_id"), accountId),
            steamId64,
            steamName,
            integer(root, "rank_tier"),
            mmr == null ? null : (int) Math.round(mmr)
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Double decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static long asLong(String value, long fallback) {
        return isDigits(value) ? Long.parseLong(value) : fallback;
    }

    /**
     * Профиль Steam-аккаунта в том виде, в котором он нужен игроку.
     *
     * @param accountId Dota {@code account_id} (то, чем ищет OpenDota)
     * @param steamId64 SteamID64, {@code null} у закрытого профиля
     * @param steamName ник для подмены, {@code null} у закрытого профиля
     * @param rankTier  ранг (1–8), {@code null} если неизвестен
     * @param mmr       примерный MMR, {@code null} если неизвестен
     */
    public record SteamProfile(
        long accountId,
        Long steamId64,
        String steamName,
        Integer rankTier,
        Integer mmr
    ) {
        /** Закрытый профиль: id есть, но ника — подменять нечего. */
        public boolean hasSteamName() {
            return steamName != null && !steamName.isBlank();
        }
    }

    /** Профиль не найден — сообщение безопасно показывать игроку. */
    public static class SteamProfileException extends IOException {
        public SteamProfileException(String message) {
            super(message);
        }
    }
}