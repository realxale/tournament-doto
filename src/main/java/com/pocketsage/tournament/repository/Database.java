package com.pocketsage.tournament.repository;

import com.pocketsage.tournament.config.EnvLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;

/**
 * Доступ к БД: общий пул соединений и миграции Flyway.
 *
 * <p>Репозитории получают базу через {@link #getInstance()} — это один пул на
 * процесс, а соединение на запрос не создаётся (его берёт Hikari и отдаёт
 * назад). Тесты используют второй конструктор с явными параметрами.
 */
public class Database implements AutoCloseable {

    /** Где лежат SQL-миграции (src/main/resources/db/migration). */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private final HikariDataSource dataSource;
    private static Database instance;

    /** Прод-конфиг: доступы берутся из .env / env.env. */
    private Database() {
        var env = new EnvLoader();
        this.dataSource = createDataSource(
            env.getDatabaseUrl(),
            env.getDatabaseLogin(),
            env.getDatabasePassword()
        );
    }

    /** Конструктор с явными настройками — нужен тестам. */
    public Database(String jdbcUrl, String login, String password) {
        this.dataSource = createDataSource(jdbcUrl, login, password);
    }

    /**
     * Пул соединений Hikari.
     *
     * <p>Значения подобраны под нагрузку бота: запросов мало, они короткие, но
     * Discord ждёт ответа быстро, поэтому важнее верхняя граница и таймаут
     * ожидания соединения, чем размер пула.
     *
     * <ul>
     *   <li>{@code maximumPoolSize} — потолок одновременных соединений;</li>
     *   <li>{@code connectionTimeout} — сколько ждать свободного соединение,
     *       прежде чем признать БД недоступной (5 с, иначе Discord уже счёл
     *       нажатие игнорированным);</li>
     *   <li>{@code idleTimeout} / {@code maxLifetime} — чтобы соединения не
     *       висели вечно и не отваливались по таймауту провайдера;</li>
     *   <li>{@code leakDetectionThreshold} — если соединение забыли вернуть,
     *       Hikari напишет об этом в лог вместо тихой утечки.</li>
     * </ul>
     */
    private static HikariDataSource createDataSource(String jdbcUrl, String login, String password) {
        var config = new HikariConfig();
        // настройка соединения с бд
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(login);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(60000);
        return new HikariDataSource(config);
    }

    /**
     * Применяет Flyway-миграции (V1, V2, ...) к базе.
     *
     * <p>Идемпотентна: повторный запуск ничего не делает, потому что Flyway
     * сверяется с таблицей {@code flyway_schema_history}. Вызывается на каждом
     * старте бота, поэтому новую миграцию достаточно положить в
     * {@code src/main/resources/db/migration} — вызов подхватит её сам.
     *
     * <p>Правило: миграции только добавляют и не переименовывают «на живых»
     * таблицах — уже применённые файлы править нельзя, Flyway сверит checksum
     * и откажется стартовать.
     */
    public void migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .load()
            .migrate();
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Единственный экземпляр базы на процесс.
     *
     * <p>synchronized — потому что к нему обращаются обработчики JDA из разных
     * потоков, а создавать два пула соединений к одной БД нельзя.
     *
     * <p>Экземпляр намеренно не сбрасывается в null: {@link #close()} закрывает
     * пул, но повторный {@code getInstance()} вернёт тот же объект. В проде не
     * мешает — процесс завершается сразу после shutdown-hook.
     */
    public static synchronized Database getInstance() {
        if (instance == null) {
            instance = new Database();
        }
        return instance;
    }
}
