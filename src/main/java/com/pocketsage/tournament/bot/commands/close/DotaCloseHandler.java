package com.pocketsage.tournament.bot.commands.close;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

/**
 * Команда {@code /create_close_dota} — точка входа в создание клоза.
 *
 * <p>Класс тонкий намеренно: он только проверяет права и показывает модалку, а
 * всю работу (категория, каналы, права, первый матч) делает {@link DotaCloseService}.
 * Так его можно читать как список «что нужно, чтобы дойти до модалки».
 *
 * <p>Идентификаторы {@code create_close_dota} и {@code dota_close_modal} —
 * единственная связь между обработчиками Discord: команда открывает модалку с этим
 * id, а {@link #onModalInteraction} ловит submit по тому же id. Меняешь строку в
 * одном месте — меняй и в другом.
 */
public class DotaCloseHandler extends ListenerAdapter {

    private final DotaCloseService service = new DotaCloseService();

    // ===== Регистрация команды =====
    @Override
    public void onReady(ReadyEvent event) {
        // upsert, а не create: повторный Ready после реконнекта не должен падать
        // из-за уже существующей команды.
        event
            .getJDA()
            .upsertCommand("create_close_dota", "Создать клоз Dota 2")
            .queue();
    }

    // ===== Ловля /create_close_dota =====
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // Слушатель общий для всех команд бота, поэтому чужие имена пропускаем.
        if (!event.getName().equals("create_close_dota")) return;
        showDotaModal(event);
    }

    // ===== Ловля submit модалки =====
    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        // Та же проверка по id: сюда прилетают submit'ы от всех модалок бота.
        if (!event.getModalId().equals("dota_close_modal")) return;
        service.handleSubmit(event);
    }

    // ===== Модалка =====
    /**
     * Проверяет права и открывает модалку с параметрами будущего клоза.
     *
     * <p>Значения селектов — короткие коды ({@code y}/{@code n}, {@code cm}/{@code ap}),
     * а не подписи: по коду сервис надёжно разбирает выбор, а подпись можно свободно
     * переводить и менять формулировку, не ломая сохранение в БД.
     */
    private void showDotaModal(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        // В личке (DM) участника нет — клоз без категории создать негде.
        if (member == null) {
            event.reply("Только на сервере.").setEphemeral(true).queue();
            return;
        }
        // Право на создание клоза — только у роли Closemod, не у самого клозера:
        // клозер ведь чужой матч, и он не должен создавать серии.
        boolean hasRole = member
            .getRoles()
            .stream()
            .anyMatch(r -> r.getName().equals("Closemod"));
        if (!hasRole) {
            event.reply("❌ Нужна роль Closemod.").setEphemeral(true).queue();
            return;
        }

        StringSelectMenu immortal = StringSelectMenu.create("immortal")
            .setPlaceholder("Immortal draft?")
            .addOption("Y", "y")
            .addOption("N", "n")
            .build();

        StringSelectMenu draft = StringSelectMenu.create("draft")
            .setPlaceholder("Gamemode?")
            .addOption("Captains Mode", "cm")
            .addOption("All Pick", "ap")
            .build();

        StringSelectMenu toxic = StringSelectMenu.create("toxic")
            .setPlaceholder("Toxic?")
            .addOption("Y", "y")
            .addOption("N", "n")
            .build();

        Modal modal = Modal.create("dota_close_modal", "Создание клоза Dota 2")
            .addComponents(
                Label.of("Immortal draft?", immortal),
                Label.of("Gamemode", draft),
                Label.of("Toxic mode", toxic)
            )
            .build();

        event.replyModal(modal).queue();
    }
}
