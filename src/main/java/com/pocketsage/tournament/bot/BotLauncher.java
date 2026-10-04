package com.pocketsage.tournament.bot;

import com.pocketsage.tournament.bot.commands.CommandRegistry;
import com.pocketsage.tournament.bot.commands.SteamBindHandler;
import com.pocketsage.tournament.bot.commands.SteamBindOldHandler;
import com.pocketsage.tournament.bot.commands.close.CloseRegistrationHandler;
import com.pocketsage.tournament.bot.commands.close.DotaCloseHandler;
import com.pocketsage.tournament.config.EnvLoader;
import com.pocketsage.tournament.repository.Database;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;

/**
 * Запуск бота: миграции БД → подключение к Discord.
 *
 * <p>Порядок обязателен: сначала схема, потом сеть. Слушатели клоза на старте уже
 * пишут в БД (см. {@code DotaCloseService}), и без миграций первый же клоз упал бы
 * с ошибкой «отношения не существует».
 *
 * <p>{@link #stop()} вызывается и из shutdown-hook, и при ошибке старта, поэтому
 * он обязан быть идемпотентным и не бросать — см. реализацию.
 */
public class BotLauncher {

    private Database database;
    private JDA jda;

    /**
     * Накатывает миграции и поднимает JDA (ждёт READY).
     *
     * <p>Блокирует вызывающий поток на {@code awaitReady()} — процесс живёт,
     * пока соединение установлено. Сама проверка успеха — в {@code Main}:
     * {@code awaitReady()} бросает {@link InterruptedException}, если поток
     * прерван.
     *
     * <p>Список слушателей — точка регистрации всех обработчиков бота. Новый
     * обработчик добавляется здесь и только здесь: сам по себе класс, даже
     * унаследовавший {@code ListenerAdapter}, Discord не увидит.
     */
    public void start() throws InterruptedException {
        var env = new EnvLoader();

        database = Database.getInstance();
        database.migrate();

        jda = JDABuilder.createDefault(env.getDiscordToken())
            .setActivity(Activity.playing("Dota 2 close"))
            .addEventListeners(
                new CommandRegistry(),
                new SteamBindHandler(),
                new SteamBindOldHandler(),
                new DotaCloseHandler(),
                new CloseRegistrationHandler()
            )
            .build();
        jda.awaitReady();
    }

    /**
     * Гасит JDA и закрывает пул соединений с БД.
     *
     * <p>Идемпотентна и не бросает исключений: вызывается и из shutdown-hook, и
     * из блока catch в {@code Main}, в том числе когда {@code start()} упал до
     * создания JDA. Поэтому поля обнуляются — повторный вызов ничего не делает.
     */
    public void stop() {
        if (jda != null) {
            jda.shutdown();
            jda = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
