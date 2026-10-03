package com.pocketsage.tournament.bot.commands;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Регистрация глобальных команд бота.
 *
 * <p>Обработчики этих команд живут в других классах, здесь только само объявление,
 * чтобы Discord их видел. {@code upsertCommand} идемпотентен: повторный вызов
 * обновляет существующую команду, а не плодит дубли — поэтому его безопасно звать
 * на каждом {@code Ready}, в том числе после реконнекта.
 */
public class CommandRegistry extends ListenerAdapter {

    // ===== Регистрация команд =====
    @Override
    public void onReady(ReadyEvent event) {
        var jda = event.getJDA();
        // /help и /info — заглушки: Discord не даёт зарегистрировать команду без
        // обработчика, а пустой список команд выглядит как «бот сломан».
        jda.upsertCommand("help", "Узнать о командах").queue();
        jda.upsertCommand("info", "Показать информацию о вас").queue();
        // create_close_dota регистрирует свой обработчик — DotaCloseHandler
        jda.upsertCommand("create_close_cs", "Создать клоз CS").queue();
    }
}
