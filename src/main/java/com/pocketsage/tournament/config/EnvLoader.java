package com.pocketsage.tournament.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Доступ к настройкам окружения.
 *
 * <p>Два источника по очереди: локальный {@code .env} (в репозиторий не попадает),
 * а если его нет — шаблон {@code env.env} из репозитория. Второй вариант нужен,
 * чтобы проект запускался «из коробки», но без реальных секретов.
 *
 * <p>Все геттеры кэшируют значение {@code Dotenv} при создании объекта: файл
 * перечитывается только один раз, зато все обращения идут к уже прочитанным
 * данным. Поэтому {@code require()} на отсутствующем ключе упадёт сразу при
 * первом обращении, а не «когда-нибудь в середине работы».
 */
public class EnvLoader {

    /** Локальные секреты (в репозиторий не попадают). */
    private static final String LOCAL_FILE = ".env";

    /** Шаблон конфига, который лежит в репозитории. */
    private static final String TEMPLATE_FILE = "env.env";

    private final Dotenv env;

    public EnvLoader() {
        this.env = load();
    }

    /** Сначала пробуем .env, если его нет — читаем шаблон env.env из репозитория. */
    private static Dotenv load() {
        if (Files.exists(Path.of(LOCAL_FILE))) {
            return Dotenv.configure().directory(".").filename(LOCAL_FILE).load();
        }
        return Dotenv.configure()
            .directory(".")
            .filename(TEMPLATE_FILE)
            .ignoreIfMissing()
            .load();
    }

    /**
     * Возвращает обязательную переменную или падает.
     *
     * <p>Пустая строка приравнена к отсутствующей: {@code DISCORD_TOKEN=} в .env —
     * это не «токен», а ошибка конфигурации, и ловить её лучше на старте.
     */
    public String require(String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Переменная " + key + " не задана в .env/env.env"
            );
        }
        return value;
    }

    public String getDiscordToken() {
        return require("DISCORD_TOKEN");
    }

    public String getDatabaseUrl() {
        return require("DATABASE_URL");
    }

    public String getDatabaseLogin() {
        return require("DATABASE_LOGIN");
    }

    public String getDatabasePassword() {
        return require("DATABASE_PASSWORD");
    }

    public String getOpenDotaApiKey() {
        return env.get("OPENDOTA_APIKEY"); // опциональный
    }
}
