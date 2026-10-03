package com.pocketsage.tournament.bot.commands.close;

import java.util.List;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

/**
 * Модалка выбора состава команды A при immortal draft.
 *
 * <p>Discord не даёт больше 5 компонентов в модалке, поэтому на команду
 * приходится по одной. Состав команды B клозер не выбирает: он собирается
 * автоматически из игроков, не попавших в A.
 *
 * <p>id модалки: {@code dota_draft:<team>:<categoryId>} — по нему же
 * находим клоз, чтобы не тащить id матча в модалку.
 */
public final class DraftModal {

    private static final String PREFIX = "dota_draft";

    /** Discord не принимает в option label больше 100 символов. */
    private static final int LABEL_LIMIT = 100;

    private DraftModal() {}

    /**
     * Модалка выбора состава одной команды.
     *
     * @param guild нужен, чтобы показать в вариантах ник игрока, а не его id
     */
    public static Modal forTeam(CloseMatch match, char team, Guild guild) {
        List<Long> members = match.participants();
        Modal.Builder modal = Modal.create(
            modalId(team, match.getCloseCategoryId()),
            "Состав команды " + (team == 'a' ? "A" : "B") +
            " — матч #" + match.getNumber()
        );

        for (int slot = 0; slot < CloseMatch.TEAM_SIZE; slot++) {
            // в каждом слоте доступны все участники: клозер сам решает,
            // кому достанется этот слот, поэтому список не сужается по позиции
            StringSelectMenu.Builder select = StringSelectMenu
                .create(fieldName(slot))
                .setPlaceholder("Слот " + (slot + 1))
                .setRequired(false);

            for (long discordId : members) {
                // label — ник для человека, value — id для разбора выбора
                select.addOption(
                    displayName(guild, discordId),
                    Long.toString(discordId)
                );
            }
            if (select.getOptions().isEmpty()) {
                continue; // матч пуст — Discord не примет селект без вариантов
            }
            modal.addComponents(
                Label.of("Слот " + (slot + 1), select.build())
            );
        }
        return modal.build();
    }

    /**
     * Ник игрока для списка выбора.
     *
     * <p>Раньше в вариантах стоял сырой {@code <@id>}: Discord всё равно
     * показывал упоминание, но в самом селекте это выглядело неопрятно и
     * занимало лимит длины. Теперь берём ник участника и режем до 100
     * символов — иначе Discord отклонит модалку.
     *
     * @param guild может быть null (тесты) — тогда показываем id
     */
    private static String displayName(Guild guild, long discordId) {
        Member member = guild == null ? null : guild.getMemberById(discordId);
        if (member == null) {
            return Long.toString(discordId);
        }
        String name = member.getEffectiveName();
        if (name == null || name.isBlank()) {
            name = member.getUser().getName();
        }
        if (name.length() > LABEL_LIMIT) {
            name = name.substring(0, LABEL_LIMIT);
        }
        return name;
    }

    /** Выбор игрока в слоте (пустой список — слот не заполнен). */
    public static List<String> chosenPlayerId(
        ModalInteractionEvent event,
        int slot
    ) {
        return event.getValue(fieldName(slot)).getAsStringList();
    }

    /** id категории клоза из id модалки. */
    public static String categoryIdOf(String modalId) {
        return modalId.substring(modalId.lastIndexOf(':') + 1);
    }

    /** Команда ('a'/'b') из id модалки; 0 — если id чужой. */
    public static char teamOf(String modalId) {
        if (!modalId.startsWith(PREFIX + ":")) {
            return 0;
        }
        String rest = modalId.substring(PREFIX.length() + 1);
        return rest.startsWith("a") ? 'a' : rest.startsWith("b") ? 'b' : 0;
    }

    private static String modalId(char team, String categoryId) {
        return PREFIX + ':' + team + ':' + categoryId;
    }

    private static String fieldName(int slot) {
        return "slot_" + slot;
    }
}