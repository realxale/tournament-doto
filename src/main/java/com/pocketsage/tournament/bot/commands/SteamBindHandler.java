package com.pocketsage.tournament.bot.commands;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Команда {@code /bind}: привязка Steam-ника к Discord-аккаунту.
 *
 * <p>Зачем это нужно: перед матчем бот подменяет ник участника на Steam-ник, и
 * подставить его можно только если игрок один раз назвал свой ник. Поэтому /bind —
 * обязательный шаг перед участием, а не украшение.
 *
 * <p>Как и {@code DotaCloseHandler}, класс только принимает ввод: проверки формы,
 * работа с БД — в {@link SteamBindService}.
 */
public class SteamBindHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SteamBindHandler.class);

    private final SteamBindService service = new SteamBindService();

    /** Ограничения формы. Discord обрежет длинное значение сам, но проверить
     *  раньше дешевле: ошибка сразу у игрока, а не тихий обрыв в БД. */
    private static final int MAX_STEAM_NAME = 64;

    // ===== Регистрация команды =====
    @Override
    public void onReady(ReadyEvent event) {
        // upsert, а не create: повторный Ready после реконнекта не должен падать
        // из-за уже существующей команды.
        event.getJDA()
            .upsertCommand("bind", "Привязать Steam name к профилю")
            .queue();
    }

    // ===== Ловля /bind =====
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // Слушатель общий для всех команд бота, поэтому чужие имена пропускаем.
        if (!event.getName().equals("bind")) return;
        showBindModal(event);
    }

    // ===== Модалка =====
    /** Показывает форму ввода Steam-ника. */
    private void showBindModal(SlashCommandInteractionEvent event) {
        // id поля steam_name читается в handleSubmit — связаны между собой.
        TextInput steamName = TextInput.create("steam_name", TextInputStyle.SHORT)
            .setPlaceholder("Например: Suma1L")
            .setRequired(true)
            .setMinLength(2)
            .setMaxLength(MAX_STEAM_NAME)
            .build();

        // id модалки должен совпадать с проверкой в onModalInteraction ниже.
        Modal modal = Modal.create("steam_bind_modal", "Привязка Steam")
            .addComponents(Label.of("Steam name", steamName))
            .build();

        event.replyModal(modal).queue();
    }

    // ===== Submit модалки =====
    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        // Та же проверка по id: сюда прилетают submit'ы от всех модалок бота.
        if (!event.getModalId().equals("steam_bind_modal")) return;
        handleSubmit(event);
    }

    /**
     * Проверяет введённое имя и сохраняет привязку.
     *
     * <p>Сначала читаем поле и отсекаем мусор, потом отвечаем — и только затем
     * пишем в БД. Порядок именно такой, чтобы игрок не ждал ответа на ввод.
     */
    private void handleSubmit(ModalInteractionEvent event) {
        long discordId = event.getUser().getIdLong();
        // Имя берём на момент привязки: players.name — это «как видели в тот
        // момент», а не «как зовут сейчас» (оно меняется при смене ника в Discord).
        String discordName = event.getUser().getEffectiveName();

        String steamName;
        try {
            steamName = event.getValue("steam_name").getAsString().trim();
        } catch (IllegalArgumentException e) {
            // такого поля в модалке нет — значит это submit чужой формы
            event.reply("❌ Ошибка чтения модалки.").setEphemeral(true).queue();
            return;
        }

        // Валидация повторяет ограничения формы: Discord не проверяет содержимое,
        // только длину, а пробелы и пустые строки пропускает.
        if (steamName.isBlank()) {
            event.reply("❌ Steam name не может быть пустым.").setEphemeral(true).queue();
            return;
        }
        if (steamName.length() > MAX_STEAM_NAME) {
            event.reply("❌ Steam name слишком длинный.").setEphemeral(true).queue();
            return;
        }

        // ===== Сохранение в БД =====
        // defer: модалку надо подтвердить за 3 секунды, а соединение с БД может занять время
        event.deferReply(true).queue(hook -> {
            try {
                SteamBindService.BindResult result = service.bind(discordId, discordName, steamName);
                String status = result.created()
                    ? "🎮 Профиль создан в базе."
                    : "♻️ Профиль обновлён.";
                hook.sendMessage("✅ Steam привязан: **" + steamName + "**\n" + status).queue();
            } catch (SQLException e) {
                // детали ошибки — только в лог, чтобы не светить доступы к БД в Discord
                log.error("Не удалось сохранить привязку Steam (discord_id={})", discordId, e);
                hook.sendMessage("❌ Не удалось сохранить привязку, попробуйте позже.").queue();
            }
        });
    }
}
