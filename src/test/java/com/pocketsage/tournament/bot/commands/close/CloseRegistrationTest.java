package com.pocketsage.tournament.bot.commands.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

/** Клоз как серия матчей: нумерация матчей, наследование параметров, кнопки. */
class CloseRegistrationTest {

    private static final String CATEGORY = "555";
    private static final long HOST = 100L;

    // Позиции кнопок в ряду matchControlButtons. Ряд содержит пять кнопок —
    // это лимит Discord на один ActionRow, поэтому порядок зафиксирован, а
    // индексы названы: нумерация не должна разъезжаться при правках тестов.
    private static final int BTN_MATCH_START = 0;
    private static final int BTN_READY_START = 1;
    private static final int BTN_GAME_START = 2;
    private static final int BTN_MATCH_DELETE = 3;
    private static final int BTN_MATCH_FINISH = 4;

    private static CloseRegistration close() {
        return new CloseRegistration(1L, CATEGORY, 7, HOST, false, "cm", true);
    }

    @Test
    void freshCloseHasNoActiveMatchAndStartsFromOne() {
        CloseRegistration close = close();

        assertNull(close.getCurrentMatch(), "матч ещё не запускали");
        assertEquals(1, close.nextMatchNumber());
    }

    @Test
    void matchNumbersGrowInsideOneClose() {
        CloseRegistration close = close();

        CloseMatch first = new CloseMatch(1L, close.getCloseId(), CATEGORY, 1, HOST, false, "cm", true);
        close.setCurrentMatch(first);
        assertSame(first, close.getCurrentMatch());

        // матч завершили — клоз ждёт следующего
        close.clearCurrentMatch();
        assertNull(close.getCurrentMatch());
        assertEquals(2, close.nextMatchNumber(), "нумерация матчей продолжается");
    }

    @Test
    void closePassesModalSettingsToItsMatches() {
        CloseRegistration close = close();

        CloseRegistration.MatchSettings settings = close.settings();

        assertEquals("cm", settings.gamemode());
        assertFalse(settings.immortalDraft());
        assertTrue(settings.toxic());
    }

    @Test
    void registryKeysCloseByCategoryId() {
        CloseRegistration close = close();
        CloseRegistrations.add(close);
        try {
            assertSame(close, CloseRegistrations.get(CATEGORY));
            assertSame(close, CloseRegistrations.remove(CATEGORY));
            assertNull(CloseRegistrations.get(CATEGORY));
        } finally {
            CloseRegistrations.remove(CATEGORY);
        }
    }

    // ===== Кнопки «управление» =====

    @Test
    void matchControlHasCreateReadyStartDeleteAndFinish() {
        List<Button> buttons = CloseRegistrations.matchControlButtons(close()).getButtons();

        assertEquals(5, buttons.size());
        assertEquals(CloseRegistrations.ACTION_MATCH_START + ":" + CATEGORY,
            buttons.get(BTN_MATCH_START).getCustomId());
        assertEquals("Создать матч", buttons.get(BTN_MATCH_START).getLabel());
        assertEquals(CloseRegistrations.ACTION_READY_START + ":" + CATEGORY,
            buttons.get(BTN_READY_START).getCustomId());
        assertEquals("Проверка готовности", buttons.get(BTN_READY_START).getLabel());
        assertEquals(CloseRegistrations.ACTION_GAME_START + ":" + CATEGORY,
            buttons.get(BTN_GAME_START).getCustomId());
        assertEquals("Начать игру", buttons.get(BTN_GAME_START).getLabel());
        assertEquals("Удалить матч", buttons.get(BTN_MATCH_DELETE).getLabel());
        assertEquals("Завершить матч", buttons.get(BTN_MATCH_FINISH).getLabel());
    }

    /** Проверять готовность имеет смысл, только когда матч объявлен и есть кому отвечать. */
    @Test
    void readyStartIsBlockedWithoutAnnouncedParticipants() {
        CloseRegistration close = close();
        assertTrue(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_READY_START).isDisabled(),
            "матч не объявлен — проверять некого"
        );

        CloseMatch match = new CloseMatch(1L, close.getCloseId(), CATEGORY, 1, HOST, false, "cm", true);
        close.setCurrentMatch(match);
        assertTrue(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_READY_START).isDisabled(),
            "карточка ещё не опубликована"
        );

        match.markAnnounced();
        assertTrue(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_READY_START).isDisabled(),
            "никто не записался"
        );

        match.signUp(100_000_000_000_000_000L);
        assertFalse(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_READY_START).isDisabled(),
            "есть записанный — можно запускать проверку"
        );
    }

    /** «Начать игру» гаснет, пока в объявленном матче не собраны все 10 игроков. */
    @Test
    void gameStartIsBlockedUntilEveryoneSignedUp() {
        CloseRegistration close = close();
        List<Button> silent = CloseRegistrations.matchControlButtons(close).getButtons();
        assertTrue(silent.get(1).isDisabled(), "матч не объявлен — играть нельзя");

        CloseMatch match = new CloseMatch(1L, close.getCloseId(), CATEGORY, 1, HOST, false, "cm", true);
        close.setCurrentMatch(match);
        assertTrue(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_GAME_START).isDisabled(),
            "карточка ещё не опубликована"
        );

        match.markAnnounced();
        assertTrue(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_GAME_START).isDisabled(),
            "никто не записался"
        );

        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(100_000_000_000_000_000L + i);
        }
        assertFalse(
            CloseRegistrations.matchControlButtons(close).getButtons().get(BTN_GAME_START).isDisabled(),
            "все 10 на месте — можно разводить по голосовым"
        );
    }

    @Test
    void startIsAvailableUntilCardIsPublished() {
        CloseRegistration close = close();
        List<Button> before = CloseRegistrations.matchControlButtons(close).getButtons();
        assertFalse(before.get(BTN_MATCH_START).isDisabled(), "матч не объявлен — можно создавать");

        CloseMatch match = new CloseMatch(1L, close.getCloseId(), CATEGORY, 1, HOST, false, "cm", true);
        close.setCurrentMatch(match);
        List<Button> silent = CloseRegistrations.matchControlButtons(close).getButtons();
        assertFalse(silent.get(BTN_MATCH_START).isDisabled(), "карточка ещё не опубликована");

        match.markAnnounced();
        List<Button> announced = CloseRegistrations.matchControlButtons(close).getButtons();
        assertTrue(announced.get(BTN_MATCH_START).isDisabled(), "матч уже идёт");
        assertFalse(announced.get(BTN_MATCH_DELETE).isDisabled(), "идущий матч можно удалить");
        assertFalse(announced.get(BTN_MATCH_FINISH).isDisabled(), "идущий матч можно завершить");
    }

    @Test
    void deleteAndFinishAreBlockedWithoutActiveMatch() {
        List<Button> buttons = CloseRegistrations.matchControlButtons(close()).getButtons();

        assertTrue(buttons.get(BTN_MATCH_DELETE).isDisabled(), "нечего удалять");
        assertTrue(buttons.get(BTN_MATCH_FINISH).isDisabled(), "нечего завершать");
    }

    @Test
    void afterFinishTheNextMatchCanBeCreated() {
        CloseRegistration close = close();
        CloseMatch first = new CloseMatch(1L, close.getCloseId(), CATEGORY, 1, HOST, false, "cm", true);
        close.setCurrentMatch(first);
        first.markAnnounced();
        first.markFinished();
        close.clearCurrentMatch();

        List<Button> buttons = CloseRegistrations.matchControlButtons(close).getButtons();

        assertFalse(
            buttons.get(BTN_MATCH_START).isDisabled(),
            "клоз ждёт следующего матча"
        );
    }

    @Test
    void closeControlOnlyDeletesTheClose() {
        List<Button> buttons = CloseRegistrations.controlButtons(CATEGORY).getButtons();

        assertEquals(1, buttons.size());
        assertEquals(
            CloseRegistrations.ACTION_CLOSE_DELETE + ":" + CATEGORY,
            buttons.get(0).getCustomId()
        );
        assertEquals("Удалить клоз", buttons.get(0).getLabel());
    }

    // ===== Immortal draft: выбор состава =====

    /**
     * Кнопка драфта одна: команду B клозер не выбирает.
     *
     * <p>После фиксации состава A кнопка гаснет — повторный выбор уже не нужен.
     */
    @Test
    void draftButtonLocksAfterTeamAIsPicked() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            true,
            "cm",
            false
        );
        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(1000L + i);
        }

        List<Button> before = CloseRegistrations.draftButtons(match).getButtons();
        assertEquals(1, before.size(), "выбирается только состав команды A");
        assertEquals(
            CloseRegistrations.ACTION_DRAFT_A + ":" + CATEGORY,
            before.get(0).getCustomId()
        );
        assertFalse(before.get(0).isDisabled(), "команду A выбрать можно");

        assertTrue(match.assignTeam('a', List.of(1000L, 1001L, 1002L, 1003L, 1004L)));

        assertTrue(
            CloseRegistrations.draftButtons(match).getButtons().get(0).isDisabled(),
            "состав команды A уже зафиксирован"
        );
    }

    /**
     * Команда B добирается из остатка: того, кто не попал в A.
     */
    @Test
    void assignRestToBuildsTeamBFromLeftovers() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            true,
            "cm",
            false
        );
        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(1000L + i);
        }

        assertTrue(match.assignTeam('a', List.of(1000L, 1001L, 1002L, 1003L, 1004L)));
        List<Long> rest = match.assignRestTo('b');

        assertEquals(5, rest.size(), "в B должно попасть 5 игроков");
        assertEquals(
            List.of(1005L, 1006L, 1007L, 1008L, 1009L),
            rest,
            "B собирается из тех, кто не выбран в A"
        );
        for (long discordId : rest) {
            assertEquals('b', match.teamOf(discordId));
        }
        assertEquals(5, match.teamSize('b'));
        assertTrue(match.assignRestTo('b').isEmpty(), "повторно добирать нечего");
    }

    @Test
    void assignTeamRejectsWrongSizeAndStrangers() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            true,
            "cm",
            false
        );
        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(1000L + i);
        }

        assertFalse(match.assignTeam('a', List.of(1000L, 1001L)), "нужно ровно 5 игроков");
        assertFalse(
            match.assignTeam('a', List.of(1000L, 1001L, 1002L, 1003L, 9999L)),
            "незаписанный игрок в состав не попадает"
        );
        assertFalse(match.hasTeam('a'), "неудачный выбор не засчитывается");
    }

    @Test
    void draftTeamsOverrideSignupOrder() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            true,
            "cm",
            false
        );
        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(1000L + i);
        }

        // игрок 1009 записан последним (слот 10 → команда b), но клозер
        // отправил его в команду A
        assertTrue(match.assignTeam('a', List.of(1009L, 1000L, 1001L, 1002L, 1003L)));
        assertTrue(match.assignTeam('b', List.of(1004L, 1005L, 1006L, 1007L, 1008L)));

        assertEquals('a', match.teamOf(1009L), "драфт важнее порядка записи");
        assertEquals('b', match.teamOf(1004L));
        assertEquals(5, match.teamMembers('a').size());
        assertEquals(5, match.teamMembers('b').size());
        assertTrue(
            match.teamMembers('a').contains(1009L),
            "переопределённый игрок в своей команде"
        );
    }

    @Test
    void withoutDraftTeamsFollowSignupOrder() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            false,
            "cm",
            false
        );
        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            match.signUp(1000L + i);
        }

        assertEquals('a', match.teamOf(1000L));
        assertEquals('a', match.teamOf(1004L));
        assertEquals('b', match.teamOf(1005L));
        assertEquals('b', match.teamOf(1009L));
        assertFalse(match.hasTeam('a'), "без драфта состав не назначается явно");
    }

    @Test
    void matchGoesToPlayingOnlyOnce() {
        CloseMatch match = new CloseMatch(
            1L,
            1L,
            CATEGORY,
            1,
            HOST,
            false,
            "cm",
            false
        );

        assertTrue(match.markPlaying());
        assertTrue(match.isPlaying());
        assertFalse(match.markPlaying(), "второй старт не проходит");
        assertFalse(match.isCollecting(), "во время игры запись закрыта");
        assertFalse(match.signUp(2000L), "во время игры нельзя записаться");
    }

    @Test
    void draftModalIdCarriesTeamAndCategory() {
        String modalId = "dota_draft:a:" + CATEGORY;

        assertEquals('a', DraftModal.teamOf(modalId));
        assertEquals('b', DraftModal.teamOf("dota_draft:b:" + CATEGORY));
        assertEquals(CATEGORY, DraftModal.categoryIdOf(modalId));
        assertEquals(0, DraftModal.teamOf("steam_bind_modal"));
        assertEquals(0, DraftModal.teamOf("dota_close_modal"));
    }

    @Test
    void everyNewButtonFitsDiscordCustomIdLimit() {
        for (String action : List.of(
            CloseRegistrations.ACTION_GAME_START,
            CloseRegistrations.ACTION_DRAFT_A
        )) {
            assertTrue(
                CloseRegistrations.buttonId(action, CATEGORY).length() <= 100,
                action + ": custom_id длиннее лимита Discord"
            );
        }
    }

    /**
     * Каждая кнопка должна находиться обработчиком.
     *
     * <p>Регрессия: новые действия не попали в список ACTIONS, и нажатия
     * молча игнорировались — кнопка «Начать игру» просто ничего не делала.
     */
    @Test
    void everyDeclaredActionIsRoutedByHandler() {
        for (String action : List.of(
            CloseRegistrations.ACTION_SIGNUP,
            CloseRegistrations.ACTION_SIGNUP_A,
            CloseRegistrations.ACTION_SIGNUP_B,
            CloseRegistrations.ACTION_SIGNOFF,
            CloseRegistrations.ACTION_READY,
            CloseRegistrations.ACTION_READY_START,
            CloseRegistrations.ACTION_MATCH_START,
            CloseRegistrations.ACTION_MATCH_DELETE,
            CloseRegistrations.ACTION_MATCH_FINISH,
            CloseRegistrations.ACTION_CLOSE_DELETE,
            CloseRegistrations.ACTION_GAME_START,
            CloseRegistrations.ACTION_DRAFT_A
        )) {
            assertEquals(
                action,
                CloseRegistrationHandler.actionOf(action + ":" + CATEGORY),
                action + ": обработчик не распознаёт собственное действие"
            );
        }
    }

    /** Кнопки, которые рисует реестр, обязаны обрабатываться. */
    @Test
    void buttonsFromRegistryAreAllHandled() {
        List<String> ids = new java.util.ArrayList<>();
        CloseRegistrations
            .controlButtons(CATEGORY)
            .getComponents()
            .forEach(component -> ids.add(component.asButton().getCustomId()));
        CloseRegistrations
            .matchControlButtons(close())
            .getComponents()
            .forEach(component -> ids.add(component.asButton().getCustomId()));

        assertFalse(ids.isEmpty(), "кнопки должны быть нарисованы");
        for (String id : ids) {
            assertNotNull(
                CloseRegistrationHandler.actionOf(id),
                "кнопка " + id + " не обрабатывается"
            );
        }
    }
}
