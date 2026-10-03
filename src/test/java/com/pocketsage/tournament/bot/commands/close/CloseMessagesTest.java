package com.pocketsage.tournament.bot.commands.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

/**
 * Что именно бот отдаёт в Discord: id/подписи кнопок, маршрутизация нажатий
 * и лимиты длины сообщений.
 */
class CloseMessagesTest {

    private static final String CATEGORY = "1234567890";

    /** Лимит Discord на всю длину текста сообщения. */
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    /** Лимит Discord на custom_id кнопки. */
    private static final int DISCORD_CUSTOM_ID_LIMIT = 100;

    private static CloseMatch match(boolean immortalDraft) {
        return new CloseMatch(1L, 1L, CATEGORY, 1, 42L, immortalDraft, "cm", false);
    }

    @Test
    void signupButtonsCarryActionAndCategoryId() {
        CloseMatch draft = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            42L,
            true,
            "cm",
            false
        );
        List<Button> buttons = CloseRegistrations.buttons(draft).getButtons();

        assertEquals("close_signup:" + CATEGORY, buttons.get(0).getCustomId());
        assertEquals("Записаться", buttons.get(0).getLabel());
        assertEquals("close_signoff:" + CATEGORY, buttons.get(1).getCustomId());
        assertEquals("Отписаться", buttons.get(1).getLabel());
    }

    /** В обычном матче игрок выбирает команду двумя отдельными кнопками. */
    @Test
    void normalMatchOffersTeamSignupButtons() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            42L,
            false,
            "cm",
            false
        );
        List<Button> buttons = CloseRegistrations.buttons(match).getButtons();

        assertEquals("close_signup_a:" + CATEGORY, buttons.get(0).getCustomId());
        assertEquals("close_signup_b:" + CATEGORY, buttons.get(1).getCustomId());
        assertEquals("close_signoff:" + CATEGORY, buttons.get(2).getCustomId());
    }

    @Test
    void readyButtonIsItsOwnAction() {
        List<Button> buttons = CloseRegistrations.readyButtons(CATEGORY).getButtons();

        assertEquals(1, buttons.size());
        assertEquals(CloseRegistrations.ACTION_READY + ":" + CATEGORY,
            buttons.get(0).getCustomId());
        assertEquals("Я готов", buttons.get(0).getLabel());
    }

    @Test
    void everyControlButtonFitsDiscordCustomIdLimit() {
        for (String action : List.of(
            CloseRegistrations.ACTION_SIGNUP,
            CloseRegistrations.ACTION_SIGNOFF,
            CloseRegistrations.ACTION_READY,
            CloseRegistrations.ACTION_READY_START,
            CloseRegistrations.ACTION_MATCH_START,
            CloseRegistrations.ACTION_MATCH_DELETE,
            CloseRegistrations.ACTION_MATCH_FINISH,
            CloseRegistrations.ACTION_CLOSE_DELETE
        )) {
            assertTrue(
                CloseRegistrations.buttonId(action, CATEGORY).length() <= DISCORD_CUSTOM_ID_LIMIT,
                action + ": custom_id длиннее лимита Discord"
            );
        }
    }

    @Test
    void pressesAreRoutedToTheRightActionAndClose() {
        assertEquals(
            CloseRegistrations.ACTION_SIGNUP,
            CloseRegistrationHandler.actionOf("close_signup:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_READY,
            CloseRegistrationHandler.actionOf("close_ready:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_READY_START,
            CloseRegistrationHandler.actionOf("match_ready_start:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_MATCH_START,
            CloseRegistrationHandler.actionOf("match_start:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_MATCH_DELETE,
            CloseRegistrationHandler.actionOf("match_delete:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_MATCH_FINISH,
            CloseRegistrationHandler.actionOf("match_finish:" + CATEGORY)
        );
        assertEquals(
            CloseRegistrations.ACTION_CLOSE_DELETE,
            CloseRegistrationHandler.actionOf("close_delete:" + CATEGORY)
        );

        assertNull(
            CloseRegistrationHandler.actionOf("close_" + CATEGORY),
            "чужой customId игнорируем"
        );
        assertNull(CloseRegistrationHandler.actionOf("other_button:" + CATEGORY));

        assertEquals(CATEGORY, CloseRegistrationHandler.closeIdOf("close_signup:" + CATEGORY));
        assertEquals(CATEGORY, CloseRegistrationHandler.categoryIdOf("match_start:" + CATEGORY));
    }

    @Test
    void matchActionsAreNotConfusedWithReadyActions() {
        assertNull(
            CloseRegistrationHandler.actionOf("match_ready:" + CATEGORY),
            "match_ready без суффикса — не наше действие"
        );
        assertEquals(
            CloseRegistrations.ACTION_READY,
            CloseRegistrationHandler.actionOf("close_ready:" + CATEGORY)
        );
    }

    @Test
    void messagesFitDiscordContentLimit() {
        CloseMatch immortal = match(true);
        CloseMatch teams = match(false);

        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            immortal.signUp(100_000_000_000_000_000L + i);
            teams.signUp(100_000_000_000_000_000L + i);
        }
        for (int i = 0; i < 5; i++) {
            immortal.confirmReady(100_000_000_000_000_000L + i);
        }

        assertTrue(
            immortal.render().length() < DISCORD_CONTENT_LIMIT,
            "карточка (immortal): " + immortal.render().length()
        );
        assertTrue(
            teams.render().length() < DISCORD_CONTENT_LIMIT,
            "карточка (команды): " + teams.render().length()
        );
        assertTrue(
            immortal.renderReadiness(1_800_000_000L).length() < DISCORD_CONTENT_LIMIT,
            "сообщение проверки готовности"
        );
        assertTrue(
            immortal.renderReadinessResult(immortal.participants()).length() <
                DISCORD_CONTENT_LIMIT,
            "итог проверки готовности"
        );
        assertTrue(
            immortal.renderResult().length() < DISCORD_CONTENT_LIMIT,
            "итоговая карточка матча"
        );
    }
}
