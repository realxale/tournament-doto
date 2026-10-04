package com.pocketsage.tournament.bot.commands.closeban;

import com.pocketsage.tournament.model.CloseBan;
import java.sql.SQLException;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Команды бана в клозах:
 * <ul>
 *   <li>{@code /close_ban} — выдать бан (роль Closemod);</li>
 *   <li>{@code /close_unban} — снять бан (роль Closemod);</li>
 *   <li>{@code /close_ban_show} — история банов игрока (роль Closemod).</li>
 * </ul>
 *
 * <p>Бан глобальный: он не привязан к клозу, и забаненный не запишется ни в
 * один. Проверяется при нажатии «Записаться» — см. {@code CloseRegistrationHandler}.
 *
 * <p>Класс только принимает ввод: срок разбирает {@link CloseBanService},
 * работа с БД — в репозиториях.
 */
public class CloseBanHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CloseBanHandler.class);

    private static final String MODAL_ID = "close_ban_modal";

    /** Discord не даёт больше 5 компонентов в одной модалке. */
    private static final int MAX_REASON_LENGTH = 1000;

    /** Достаточно для «8760» — дальше всё равно режет MAX_HOURS. */
    private static final int MAX_HOURS_LENGTH = 5;

    private final CloseBanService service = new CloseBanService();

    @Override
    public void onReady(ReadyEvent event) {
        // upsert, а не create: повторный Ready после реконнекта не должен падать
        event.getJDA().upsertCommand("close_ban", "Забанить игрока в клозах").queue();
        event
            .getJDA()
            .upsertCommand(
                Commands.slash("close_unban", "Снять бан с игрока")
                    .addOption(OptionType.USER, "user", "Игрок", true)
            )
            .queue();
        event
            .getJDA()
            .upsertCommand(
                Commands.slash("close_ban_show", "История банов игрока")
                    .addOption(OptionType.USER, "user", "Игрок", true)
            )
            .queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "close_ban" -> handleBanCommand(event);
            case "close_unban" -> handleUnbanCommand(event);
            case "close_ban_show" -> handleShowCommand(event);
            default -> {
                // Слушатель общий для всех команд бота — чужие имена пропускаем
            }
        }
    }

    // ===== /close_ban =====

    private void handleBanCommand(SlashCommandInteractionEvent event) {
        if (!requireModerator(event)) {
            return;
        }
        showBanModal(event);
    }

    /** Модалка выдачи бана: игрок, причина и срок в часах. */
    private void showBanModal(SlashCommandInteractionEvent event) {
        // mention вставляется прямо из Discord — он выглядит как <@123...>
        TextInput player = TextInput.create("ban_player", TextInputStyle.SHORT)
            .setPlaceholder("1234567890 или mention")
            .setRequired(true)
            .setMinLength(3)
            .setMaxLength(200)
            .build();

        TextInput reason = TextInput.create("ban_reason", TextInputStyle.PARAGRAPH)
            .setPlaceholder("Например: AFK на 5 минут в третьем матче")
            .setRequired(true)
            .setMinLength(1)
            .setMaxLength(MAX_REASON_LENGTH)
            .build();

        // Пусто = навсегда: игрок не обязан ничего вводить
        TextInput hours = TextInput.create("ban_hours", TextInputStyle.SHORT)
            .setPlaceholder("3 = три часа, пусто = навсегда")
            .setRequired(false)
            .setMinLength(0)
            .setMaxLength(MAX_HOURS_LENGTH)
            .build();

        event
            .replyModal(
                Modal.create(MODAL_ID, "Бан игрока в клозах")
                    .addComponents(
                        Label.of("Игрок", player),
                        Label.of("Причина", reason),
                        Label.of("Срок в часах", hours)
                    )
                    .build()
            )
            .queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals(MODAL_ID)) return;

        // Право проверяем повторно: пока форма открыта, роль могли снять
        if (!hasClosemodRole(event.getMember())) {
            event.reply("❌ Нужна роль Closemod.").setEphemeral(true).queue();
            return;
        }

        String rawPlayer;
        String reason;
        String rawHours;
        try {
            rawPlayer = event.getValue("ban_player").getAsString();
            reason = event.getValue("ban_reason").getAsString();
            rawHours = event.getValue("ban_hours").getAsString();
        } catch (IllegalArgumentException e) {
            // такого поля нет — значит это submit чужой формы
            event.reply("❌ Ошибка чтения модалки.").setEphemeral(true).queue();
            return;
        }

        long targetId;
        try {
            targetId = parseUserId(rawPlayer);
        } catch (IllegalArgumentException e) {
            event.reply("❌ " + e.getMessage()).setEphemeral(true).queue();
            return;
        }

        int hours;
        try {
            hours = CloseBanService.parseHours(rawHours);
        } catch (IllegalArgumentException e) {
            event.reply("❌ " + e.getMessage()).setEphemeral(true).queue();
            return;
        }

        // Отвечаем сразу: БД может не уложиться в 3 секунды
        event.deferReply(true).queue(hook ->
            applyBan(hook, event, targetId, reason, hours)
        );
    }

    private void applyBan(
        net.dv8tion.jda.api.interactions.InteractionHook hook,
        ModalInteractionEvent event,
        long targetId,
        String reason,
        int hours
    ) {
        // Игрок должен быть на сервере: иначе бан ушёл бы в пустоту
        Member target = event.getGuild().getMemberById(targetId);
        if (target == null) {
            hook.sendMessage("❌ Игрок не найден на сервере.").queue();
            return;
        }

        try {
            CloseBan ban = service.issueBan(targetId, reason, hours);
            hook.sendMessage(CloseBanService.formatIssued(ban)).queue();
        } catch (SQLException e) {
            // детали — в лог: наружу нельзя светить доступы к БД
            log.error("Не удалось выдать бан (player={})", targetId, e);
            hook.sendMessage("❌ Не удалось сохранить бан, попробуй позже.").queue();
        }
    }

    // ===== /close_unban =====

    private void handleUnbanCommand(SlashCommandInteractionEvent event) {
        if (!requireModerator(event)) {
            return;
        }
        Member target = event.getOption("user") == null
            ? null
            : event.getOption("user").getAsMember();
        if (target == null) {
            event.reply("❌ Игрок не найден на сервере.").setEphemeral(true).queue();
            return;
        }

        long targetId = target.getIdLong();
        event.deferReply(true).queue(hook -> {
            try {
                boolean lifted = service.liftBan(targetId, event.getUser().getIdLong());
                hook
                    .sendMessage(
                        lifted
                            ? "✅ Бан снят."
                            : "ℹ️ У игрока нет действующего бана."
                    )
                    .queue();
            } catch (SQLException e) {
                log.error("Не удалось снять бан (player={})", targetId, e);
                hook.sendMessage("❌ Не удалось снять бан, попробуй позже.").queue();
            }
        });
    }

    // ===== /close_ban_show =====

    private void handleShowCommand(SlashCommandInteractionEvent event) {
        if (!requireModerator(event)) {
            return;
        }
        Member target = event.getOption("user") == null
            ? null
            : event.getOption("user").getAsMember();
        if (target == null) {
            event.reply("❌ Игрок не найден на сервере.").setEphemeral(true).queue();
            return;
        }

        long targetId = target.getIdLong();
        event.deferReply(true).queue(hook -> {
            try {
                hook
                    .sendMessage(
                        CloseBanService.formatHistory(service.history(targetId))
                    )
                    .queue();
            } catch (SQLException e) {
                log.error("Не удалось прочитать историю банов (player={})", targetId, e);
                hook.sendMessage("❌ Не удалось прочитать историю, попробуй позже.").queue();
            }
        });
    }

    // ===== Общее =====

    /** Проверка роли с общим ответом. */
    private boolean requireModerator(GenericCommandInteractionEvent event) {
        if (event.getMember() == null) {
            event.reply("Только на сервере.").setEphemeral(true).queue();
            return false;
        }
        if (!hasClosemodRole(event.getMember())) {
            event.reply("❌ Нужна роль Closemod.").setEphemeral(true).queue();
            return false;
        }
        return true;
    }

    /** Роль Closemod нужна, чтобы банить, разбанивать и смотреть историю. */
    private static boolean hasClosemodRole(Member member) {
        return member != null
            && member
                .getRoles()
                .stream()
                .anyMatch(r -> r.getName().equalsIgnoreCase("Closemod"));
    }

    /** Разбирает ввод игрока: голый id или mention вида {@code <@1234567890>}. */
    static long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Укажи игрока.");
        }
        String value = raw.strip().replace("<@", "").replace(">", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Не похоже на Discord id игрока.");
        }
    }
}
