package com.pocketsage.tournament.bot.commands.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

/**
 * Логика матча: запись, слоты, команды, дубликаты, проверка готовности.
 *
 * <p>Клоз — серия матчей, поэтому состав и проверка готовности живут в
 * {@link CloseMatch}, а последовательность матчей — в {@link CloseRegistration}.
 */
class CloseMatchTest {

    private static final long HOST = 100L;
    private static final String CATEGORY = "555";

    /** Матч #1 внутри клоза #1 — id тут произвольные. */
    private static CloseMatch match(boolean immortalDraft, String gamemode, boolean toxic) {
        return new CloseMatch(1L, 1L, CATEGORY, 1, HOST, immortalDraft, gamemode, toxic);
    }

    @Test
    void signUpThenSignOffChangesMessage() {
        CloseMatch match = match(false, "ap", false);

        assertTrue(match.signUp(201L));
        assertEquals(1, match.size());
        assertTrue(match.render().contains("<@201>"), "ник должен попасть в текст");

        assertTrue(match.signOff(201L));
        assertEquals(0, match.size());
        assertFalse(match.render().contains("<@201>"), "ник должен исчезнуть");
        assertTrue(match.render().contains("Пусто"));
    }

    @Test
    void signUpTwiceIsRejected() {
        CloseMatch match = match(false, "cm", false);

        assertTrue(match.signUp(201L));
        assertFalse(match.signUp(201L), "повторная запись того же игрока");
        assertEquals(1, match.size());
    }

    @Test
    void signOffStrangerIsRejected() {
        assertFalse(match(false, "cm", false).signOff(201L));
    }

    @Test
    void eleventhPlayerIsRejectedAndMatchIsFull() {
        CloseMatch match = match(true, "cm", true);

        for (int i = 0; i < CloseMatch.SLOTS; i++) {
            assertTrue(match.signUp(1000L + i), "слот " + (i + 1));
        }

        assertTrue(match.isFull());
        assertFalse(match.signUp(9999L), "11-й игрок не должен записаться");
        assertEquals(CloseMatch.SLOTS, match.size());
    }

    @Test
    void finishedMatchDoesNotAcceptSignups() {
        CloseMatch match = match(true, "cm", false);
        match.markFinished();

        assertFalse(match.isCollecting());
        assertFalse(match.signUp(201L), "доигранный матч не набирает игроков");
        assertEquals(0, match.size());
    }

    @Test
    void teamsFollowSignupOrder() {
        CloseMatch match = match(false, "ap", true);
        for (int i = 0; i < 6; i++) {
            match.signUp(1000L + i);
        }

        assertEquals('a', match.teamOf(1000L));
        assertEquals('a', match.teamOf(1004L), "пятый игрок — команда A");
        assertEquals('b', match.teamOf(1005L), "шестой игрок — уже B");

        String text = match.render();
        int teamAStart = text.indexOf("Команда A");
        int teamBStart = text.indexOf("Команда B");
        assertTrue(teamAStart > 0 && teamBStart > teamAStart, "две команды в тексте");

        String teamA = text.substring(teamAStart, teamBStart);
        String teamB = text.substring(teamBStart);
        for (int i = 0; i < CloseMatch.TEAM_SIZE; i++) {
            assertTrue(teamA.contains("<@" + (1000L + i) + ">"), "слот A" + (i + 1));
        }
        assertTrue(teamB.contains("<@1005>"), "шестой игрок уходит в команду B");
        assertFalse(teamA.contains("<@1005>"));
    }

    @Test
    void immortalDraftHasTenPlainSlots() {
        String text = match(true, "cm", false).render();

        assertTrue(text.contains("<@100>"), "клозер упоминанием");
        assertTrue(text.contains("**Immortal draft:** да"));
        assertTrue(text.contains("**Матч:** #1"), "в карточке виден номер матча");
        assertFalse(text.contains("Команда A"), "без immortal draft команд нет");
        for (int slot = 1; slot <= CloseMatch.SLOTS; slot++) {
            assertTrue(
                text.contains("`" + slot + "`"),
                "нет строки слота " + slot
            );
        }
        assertFalse(text.contains("slot:"), "сырые эмодзи слотов не показываем");
    }

    @Test
    void nonImmortalDraftShowsGamemodeToxicAndCounter() {
        CloseMatch match = match(false, "ap", true);
        for (int i = 0; i < 6; i++) {
            match.signUp(1000L + i);
        }

        String text = match.render();
        assertTrue(text.contains("**Gamemode:** All Pick"));
        assertTrue(text.contains("**Toxic:** да"));
        assertTrue(text.contains("**Мест занято:** 6/10"));
    }

    @Test
    void finishedMatchRendersLockedResult() {
        CloseMatch match = match(true, "cm", false);
        match.signUp(201L);
        match.markFinished();

        String text = match.renderResult();
        assertTrue(text.contains("Матч завершён"));
        assertTrue(text.contains("<@201>"), "итоговый состав сохраняется в тексте");
    }

    @Test
    void confirmReadyOnlyCountsSignedUpPlayers() {
        CloseMatch match = match(true, "cm", false);
        match.signUp(201L);

        assertFalse(match.confirmReady(202L), "не записан — засчитывать нечего");
        assertTrue(match.confirmReady(201L));
        assertTrue(match.isReady(201L));
        assertFalse(match.confirmReady(201L), "повторное подтверждение");
    }

    @Test
    void newReadinessCheckResetsConfirmations() {
        CloseMatch match = match(true, "cm", false);
        match.signUp(201L);
        match.confirmReady(201L);

        match.startReadinessCheck();

        assertFalse(match.isReady(201L));
        assertEquals(List.of(201L), match.notReady());
    }

    @Test
    void readinessWindowSignsOffSilentPlayers() {
        CloseMatch match = match(false, "ap", false);
        match.signUp(1001L);
        match.signUp(1002L);
        match.signUp(1003L);
        match.confirmReady(1001L);
        match.confirmReady(1003L);

        assertEquals(List.of(1002L), match.notReady());

        List<Long> kicked = match.signOffNotReady();

        assertEquals(List.of(1002L), kicked, "выписывается только молчащий");
        assertEquals(List.of(1001L, 1003L), match.participants());
        assertFalse(match.render().contains("<@1002>"), "выписанный пропал из карточки");
        assertTrue(match.render().contains("Пусто"), "слот освободился");
    }

    @Test
    void readinessMessageShowsCountdownAndWhoIsMissing() {
        CloseMatch match = match(true, "cm", false);
        match.signUp(201L);
        match.signUp(202L);
        match.confirmReady(202L);

        String text = match.renderReadiness(1_800_000_000L);

        assertTrue(text.contains("Проверка готовности"));
        assertTrue(text.contains("<t:1800000000:R>"), "живой отсчёт рисует сам Discord");
        assertTrue(text.contains("Клозер: <@100>"));
        assertTrue(text.contains("Подтвердили (1): <@202>"));
        assertTrue(text.contains("Ждём (1): <@201>"));
    }

    @Test
    void readinessWithoutPlayersSaysNobodySignedUp() {
        assertTrue(
            match(true, "cm", false)
                .renderReadiness(1L)
                .contains("Пока никто не записан")
        );
    }

    @Test
    void readinessResultListsKickedPlayers() {
        CloseMatch match = match(true, "cm", false);

        assertTrue(match.renderReadinessResult(List.of()).contains("никто не выписан"));
        assertTrue(match.renderReadinessResult(List.of(1L, 2L)).contains("<@1>, <@2>"));
    }

    @Test
    void matchRemembersWhereItsCardLives() {
        CloseMatch match = match(false, "cm", false);

        assertNull(match.getSignupMessageId(), "карточка ещё не опубликована");

        match.markSignupMessage(42L, 4242L);

        assertEquals(42L, match.getSignupChannelId());
        assertEquals(4242L, match.getSignupMessageId());
    }

    @Test
    void announcedFlagTracksPublishedCard() {
        CloseMatch match = match(false, "cm", false);

        assertFalse(match.isAnnounced(), "при создании клоза карточки ещё нет");
        match.markAnnounced();
        assertTrue(match.isAnnounced());
    }

    // ===== Кнопки карточки матча =====

    @Test
    void normalMatchOffersTeamChoice() {
        List<Button> buttons = CloseRegistrations
            .buttons(match(false, "cm", false))
            .getButtons();

        assertEquals(3, buttons.size(), "без кнопки проверки готовности");
        assertEquals(
            CloseRegistrations.ACTION_SIGNUP_A + ":" + CATEGORY,
            buttons.get(0).getCustomId()
        );
        assertEquals("В команду A", buttons.get(0).getLabel());
        assertEquals(
            CloseRegistrations.ACTION_SIGNUP_B + ":" + CATEGORY,
            buttons.get(1).getCustomId()
        );
        assertEquals("В команду B", buttons.get(1).getLabel());
        assertEquals("Отписаться", buttons.get(2).getLabel());
    }

    @Test
    void draftKeepsSingleSignupButton() {
        List<Button> buttons = CloseRegistrations
            .buttons(match(true, "cm", false))
            .getButtons();

        assertEquals(2, buttons.size(), "при драфте команду выбирает клозер");
        assertEquals(
            CloseRegistrations.ACTION_SIGNUP + ":" + CATEGORY,
            buttons.get(0).getCustomId()
        );
        assertEquals("Записаться", buttons.get(0).getLabel());
    }

    @Test
    void teamButtonClosesOnlyWhenThatTeamIsFull() {
        CloseMatch match = match(false, "cm", false);
        for (int i = 0; i < CloseMatch.TEAM_SIZE; i++) {
            match.signUpToTeam(1000L + i, 'a');
        }

        List<Button> buttons = CloseRegistrations.buttons(match).getButtons();

        assertTrue(buttons.get(0).isDisabled(), "команда A собрана");
        assertFalse(buttons.get(1).isDisabled(), "в команде B ещё есть места");
        assertFalse(buttons.get(2).isDisabled(), "отписаться можно всегда");
    }

    @Test
    void fullMatchClosesBothTeamButtons() {
        CloseMatch full = match(false, "cm", false);
        for (int i = 0; i < CloseMatch.TEAM_SIZE; i++) {
            full.signUpToTeam(1000L + i, 'a');
            full.signUpToTeam(2000L + i, 'b');
        }

        List<Button> buttons = CloseRegistrations.buttons(full).getButtons();

        assertTrue(buttons.get(0).isDisabled(), "команда A собрана");
        assertTrue(buttons.get(1).isDisabled(), "команда B собрана");
        assertFalse(buttons.get(2).isDisabled(), "отписаться можно всегда");
    }

    @Test
    void finishedMatchDisablesSignup() {
        CloseMatch finished = match(false, "cm", false);
        finished.markFinished();

        List<Button> buttons = CloseRegistrations.buttons(finished).getButtons();

        assertTrue(buttons.get(0).isDisabled(), "доигранный матч не набирает игроков");
        assertTrue(buttons.get(1).isDisabled());
        assertFalse(buttons.get(2).isDisabled(), "отписаться можно всегда");
    }

    // ===== Выбор команды при записи =====

    @Test
    void playerChoosesTeamAtSignup() {
        CloseMatch match = match(false, "cm", false);

        assertTrue(match.signUpToTeam(201L, 'a'));
        assertTrue(match.signUpToTeam(202L, 'b'));

        assertEquals('a', match.teamOf(201L));
        assertEquals('b', match.teamOf(202L));
        assertEquals(1, match.teamSize('a'));
        assertEquals(1, match.teamSize('b'));
        assertTrue(match.teamMembers('a').contains(201L));
        assertTrue(match.teamMembers('b').contains(202L));
    }

    @Test
    void teamChoiceIgnoresSignupOrder() {
        CloseMatch match = match(false, "cm", false);

        // первый записавшийся уходит в B, второй — в A
        assertTrue(match.signUpToTeam(201L, 'b'));
        assertTrue(match.signUpToTeam(202L, 'a'));

        assertEquals('b', match.teamOf(201L), "выбор игрока важнее порядка");
        assertEquals('a', match.teamOf(202L));
    }

    @Test
    void sixthPlayerInSameTeamIsRejected() {
        CloseMatch match = match(false, "cm", false);
        for (int i = 0; i < CloseMatch.TEAM_SIZE; i++) {
            assertTrue(match.signUpToTeam(1000L + i, 'a'), "слот " + (i + 1));
        }

        assertTrue(match.isTeamFull('a'));
        assertFalse(match.signUpToTeam(9999L, 'a'), "шестой в команду A не влезает");
        assertFalse(match.isFull(), "при этом в матче есть свободные места");
        assertTrue(match.signUpToTeam(9999L, 'b'), "в команду B ещё можно");
    }

    @Test
    void signingTwiceIsRejectedEvenForOtherTeam() {
        CloseMatch match = match(false, "cm", false);

        assertTrue(match.signUpToTeam(201L, 'a'));
        assertFalse(match.signUpToTeam(201L, 'b'), "повторная запись в другую команду");
        assertEquals(1, match.size());
        assertEquals('a', match.teamOf(201L), "команда не поменялась");
    }

    @Test
    void signOffFreesTheTeamSlot() {
        CloseMatch match = match(false, "cm", false);
        for (int i = 0; i < CloseMatch.TEAM_SIZE; i++) {
            match.signUpToTeam(1000L + i, 'a');
        }
        assertTrue(match.isTeamFull('a'));

        assertTrue(match.signOff(1000L));
        assertFalse(match.isTeamFull('a'), "слот освободился");
        assertEquals(CloseMatch.TEAM_SIZE - 1, match.teamSize('a'));
        assertTrue(match.signUpToTeam(9999L, 'a'), "можно дозаписаться");
    }

    @Test
    void finishedMatchRejectsTeamSignup() {
        CloseMatch match = match(false, "cm", false);
        match.markFinished();

        assertFalse(match.signUpToTeam(201L, 'a'));
        assertEquals(0, match.size());
    }

    @Test
    void cardShowsChosenTeamsWithCounters() {
        CloseMatch match = match(false, "cm", false);
        match.signUpToTeam(201L, 'a');
        match.signUpToTeam(202L, 'b');

        String text = match.render();
        int teamA = text.indexOf("Команда A");
        int teamB = text.indexOf("Команда B");
        String blockA = text.substring(teamA, teamB);
        String blockB = text.substring(teamB);

        assertTrue(text.contains("Команда A** (1/5)"), "счётчик команды A");
        assertTrue(text.contains("Команда B** (1/5)"), "счётчик команды B");
        assertTrue(blockA.contains("<@201>"), "игрок A в своей команде");
        assertFalse(blockA.contains("<@202>"));
        assertTrue(blockB.contains("<@202>"), "игрок B в своей команде");
        assertFalse(blockB.contains("<@201>"));
    }
}
