package com.pocketsage.tournament;

import com.pocketsage.tournament.bot.BotLauncher;
import com.pocketsage.tournament.config.EnvLoader;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;

/**
 * Точка входа.
 *
 * <p>Задача класса — превратить «упал бот» в понятную запись в stderr и ненулевой
 * код возврата, а не в стектрейс где-то внутри JDA. Всё остальное (настройки
 * подключения, миграции, слушатели) — в {@link BotLauncher}.
 *
 * <p>Поток запуска: читаем конфиг → поднимаем бота → регистрируем shutdown-hook.
 * Hook ставится <em>до</em> {@link BotLauncher#start()}, чтобы Ctrl+C и SIGTERM
 * гасили JDA и закрывали пул БД даже если бот упал на середине подключения.
 */
public class Main {

    /**
     * Запускает бота и блокирует процесс до сигнала остановки.
     *
     * <p>Исключения обрабатываются по отдельности, а не одним catch: по типу
     * видно, что именно сломалось — неверный токен, прерывание или конфликт
     * состояния JDA — и пользователю не показывается лишний шум.
     */
    public static void main(String[] args) {
        // Конфиг читается до старта, чтобы падение на отсутствии .env было
        // сразу и с внятным сообщением, а не через 10 секунд ожидания JDA.
        EnvLoader env = new EnvLoader();
        // require() бросит исключение с именем переменной, если .env пуст или нет
        // нужных ключей. Раньше токен здесь же печатался в stdout — это убрано:
        // токен попадал в логи и в консоль хоста.
        env.require("DISCORD_TOKEN");

        BotLauncher bot = new BotLauncher();
        try {
            Runtime.getRuntime().addShutdownHook(
                new Thread(bot::stop, "bot-shutdown")
            );
            bot.start();
        } catch (IllegalStateException e) {
            System.err.println("Не удалось запустить бота: " + e.getMessage());
            bot.stop();
            System.exit(1);
        } catch (InvalidTokenException e) {
            System.err.println(
                "Не удалось запустить бота: неверный DISCORD_TOKEN (" +
                    e.getMessage() +
                    ")"
            );
            bot.stop();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Запуск бота прерван.");
            bot.stop();
            System.exit(1);
        }
    }
}
