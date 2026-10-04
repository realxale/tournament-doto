package com.pocketsage.tournament.bot.commands.closeban;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pocketsage.tournament.model.CloseBan;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Логика банов без обращения к БД: разбор срока в часах, «действует ли бан
 * сейчас» и тексты, которые видит игрок.
 *
 * <p>Проверять тексты выгодно здесь — они уезжают в Discord, и ошибка в них
 * видна игроку, хотя сама логика работает верно.
 */
class CloseBanTest {

    private static final long PLAYER = 42L;
    private static final long MODERATOR = 7L;


    /**
     * Бан, выданный 3 часа назад: срок считается как timeEnd - createdAt,
     * поэтому в created_at кладём «3 часа назад минус срок».
     */
    private static CloseBan ban(Instant timeEnd, boolean lifted) {
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        return new CloseBan(
            PLAYER,
            "AFK на 5 минут",
            createdAt,
            timeEnd,
            lifted ? Instant.now() : null
        );
    }

    // ===== Срок в часах =====

    @Test
    void emptyDurationMeansForever() {
        assertEquals(CloseBanService.FOREVER, CloseBanService.parseHours(null));
        assertEquals(CloseBanService.FOREVER, CloseBanService.parseHours(""));
        assertEquals(CloseBanService.FOREVER, CloseBanService.parseHours("   "));
    }

    @Test
    void zeroAlsoMeansForever() {
        assertEquals(CloseBanService.FOREVER, CloseBanService.parseHours("0"));
    }

    @Test
    void plainNumberIsTakenAsHours() {
        assertEquals(3, CloseBanService.parseHours("3"));
        assertEquals(48, CloseBanService.parseHours(" 48 "));
    }

    @Test
    void nonNumericDurationIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CloseBanService.parseHours("неделя")
        );
    }

    @Test
    void negativeDurationIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CloseBanService.parseHours("-5")
        );
    }

    @Test
    void absurdlyLongDurationIsRejected() {
        // без ограничения «99999» превратилось бы в дату через 11 лет
        assertThrows(
            IllegalArgumentException.class,
            () -> CloseBanService.parseHours("99999")
        );
    }

    @Test
    void durationBoundaryIsAccepted() {
        assertEquals(
            CloseBanService.MAX_HOURS,
            CloseBanService.parseHours(String.valueOf(CloseBanService.MAX_HOURS))
        );
    }

    @Test
    void hoursAreFormattedWithoutDeclensions() {
        assertEquals("навсегда", CloseBanService.formatHours(CloseBanService.FOREVER));
        assertEquals("1h", CloseBanService.formatHours(1));
        assertEquals("24h", CloseBanService.formatHours(24));
        assertEquals("8760h", CloseBanService.formatHours(8760));
    }

    // ===== Действие бана =====

    @Test
    void foreverBanNeverExpires() {
        CloseBan forever = ban(null, false);
        assertTrue(forever.isForever());
        assertTrue(forever.isActiveAt(Instant.now()));
        assertTrue(
            forever.isActiveAt(Instant.now().plus(3650, ChronoUnit.DAYS)),
            "бессрочный бан не истекает сам по себе"
        );
    }

    @Test
    void banWithFutureEndIsActive() {
        CloseBan timed = ban(Instant.now().plus(3, ChronoUnit.HOURS), false);
        assertFalse(timed.isForever());
        assertTrue(timed.isActiveAt(Instant.now()));
    }

    @Test
    void banWithPastEndIsNotActive() {
        CloseBan expired = ban(Instant.now().minus(1, ChronoUnit.HOURS), false);
        assertFalse(
            expired.isActiveAt(Instant.now()),
            "по окончании срока бан перестаёт действовать сам"
        );
    }

    @Test
    void liftedBanIsNeverActive() {
        CloseBan lifted = ban(Instant.now().plus(3, ChronoUnit.HOURS), true);
        assertFalse(
            lifted.isActiveAt(Instant.now()),
            "снятый бан не действует даже до конца своего срока"
        );
    }

    @Test
    void durationIsComputedFromTimeEndAndCreatedAt() {
        // колонки duration_hours нет — срок выводится как разница дат
        Instant created = Instant.now().minus(30, ChronoUnit.MINUTES);
        CloseBan timed = new CloseBan(
            PLAYER,
            "AFK",
            created,
            created.plus(3, ChronoUnit.HOURS),
            null
        );

        assertEquals(3L, timed.durationHours());
    }

    @Test
    void expiredBanNeverReportsNegativeDuration() {
        // истёкший бан мог простоять дольше срока — в тексте это «0h»,
        // а не «-5h»
        CloseBan expired = ban(Instant.now().minus(1, ChronoUnit.HOURS), false);
        assertEquals(0L, expired.durationHours());
    }

    @Test
    void foreverBanHasZeroDuration() {
        assertEquals(0L, ban(null, false).durationHours(), "у бессрочного бана срока нет");
    }

    // ===== Тексты =====

    @Test
    void rejectionMessageCarriesReason() {
        String message = CloseBanService.formatRejection(
            ban(Instant.now().plus(3, ChronoUnit.HOURS), false)
        );

        assertTrue(message.contains("AFK на 5 минут"), "причина обязана попасть в текст");
        assertTrue(message.contains("забанены"), "игрок должен понять причину отказа");
        assertTrue(message.contains("3h"), "срок виден сразу");
    }

    @Test
    void rejectionMessageUsesDiscordTimestamp() {
        Instant end = Instant.now().plus(3, ChronoUnit.HOURS);
        String message = CloseBanService.formatRejection(ban(end, false));

        assertTrue(
            message.contains("<t:" + end.getEpochSecond() + ":F>"),
            "точное время отображает сам клиент Discord"
        );
    }

    @Test
    void foreverRejectionHasNoTimestamp() {
        String message = CloseBanService.formatRejection(ban(null, false));

        assertTrue(message.contains("навсегда"));
        assertFalse(
            message.contains("<t:"),
            "у бессрочного бана нет даты — подставлять нечего"
        );
    }

    @Test
    void issuedMessageMentionsDurationAndReason() {
        String message = CloseBanService.formatIssued(
            ban(Instant.now().plus(3, ChronoUnit.HOURS), false)
        );

        assertTrue(message.contains("3h"));
        assertTrue(message.contains("AFK на 5 минут"));
    }

    @Test
    void emptyHistoryIsReportedPlainly() {
        assertEquals("📋 Банов нет.", CloseBanService.formatHistory(List.of()));
    }

    @Test
    void historyMarksActiveBan() {
        String message = CloseBanService.formatHistory(
            List.of(ban(Instant.now().plus(3, ChronoUnit.HOURS), false))
        );

        assertTrue(message.contains("АКТИВЕН"));
        assertTrue(message.contains("AFK на 5 минут"));
    }

    @Test
    void historyKeepsExpiredAndLiftedBans() {
        String message = CloseBanService.formatHistory(
            List.of(
                ban(Instant.now().minus(2, ChronoUnit.HOURS), false),
                ban(Instant.now().minus(1, ChronoUnit.HOURS), true)
            )
        );

        assertTrue(message.contains("истёк"), "истёкший бан остаётся в истории");
        assertTrue(message.contains("снят"), "снятый бан остаётся в истории");
        assertFalse(message.contains("АКТИВЕН"));
    }

    // ===== Разбор id из формы =====

    @Test
    void plainIdIsParsed() {
        assertEquals(1234567890L, CloseBanHandler.parseUserId("1234567890"));
    }

    @Test
    void mentionIsParsed() {
        // игрок может вставить mention прямо из Discord
        assertEquals(1234567890L, CloseBanHandler.parseUserId("<@1234567890>"));
        assertEquals(1234567890L, CloseBanHandler.parseUserId("  <@1234567890>  "));
    }

    @Test
    void nicknameInsteadOfIdIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CloseBanHandler.parseUserId("Suma1L")
        );
    }

    @Test
    void emptyIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CloseBanHandler.parseUserId(""));
        assertThrows(IllegalArgumentException.class, () -> CloseBanHandler.parseUserId(null));
    }
}
