package com.pocketsage.tournament.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Бан игрока в клозах — строка таблицы {@code close_ban}.
 *
 * <p>Бан глобальный: он не привязан к клозу и мешает записаться в любой.
 *
 * <p><b>Срок выводится, а не хранится.</b> Отдельной колонки «на сколько часов»
 * нет: {@code time_end} и есть срок, а «на сколько выдали» считается как
 * {@code timeEnd - createdAt}. Для бессрочного бана {@code timeEnd == null}.
 *
 * <p><b>Бессрочность и снятие — это NULL.</b> {@code timeEnd == null} значит
 * «навсегда», {@code liftedAt == null} значит «ещё действует». Два отдельных
 * флага для этого не нужны и могли бы разойтись с фактическим сроком.
 */
public class CloseBan {

    private long discordId; // к кому применён, NOT NULL
    private String reason; // NOT NULL
    private Instant createdAt;
    private Instant timeEnd; // null = навсегда
    private Instant liftedAt; // null = бан действует

    public CloseBan(
        long discordId,
        String reason,
        Instant createdAt,
        Instant timeEnd,
        Instant liftedAt
    ) {
        this.discordId = discordId;
        this.reason = reason;
        this.createdAt = createdAt;
        this.timeEnd = timeEnd;
        this.liftedAt = liftedAt;
    }

    /**
     * Бан действует сейчас: не снят и либо бессрочный, либо срок не прошёл.
     *
     * <p>Время передаётся аргументом, а не берётся внутри — так проверку можно
     * прогнать на любой момент в тестах.
     */
    public boolean isActiveAt(Instant now) {
        if (isLifted()) {
            return false;
        }
        return timeEnd == null || timeEnd.isAfter(now);
    }

    /** Бессрочный бан — срока нет вовсе. */
    public boolean isForever() {
        return timeEnd == null;
    }

    /** Снят досрочно через /close_unban. */
    public boolean isLifted() {
        return liftedAt != null;
    }

    /**
     * На сколько часов бан был выдан. Для бессрочного — 0.
     *
     * <p>Считается, а не хранится: иначе две колонки описывали бы одно и то же
     * и могли бы разойтись.
     */
    public long durationHours() {
        if (timeEnd == null || createdAt == null) {
            return 0;
        }
        long hours = Duration.between(createdAt, timeEnd).toHours();
        // у истёкшего бана разница отрицательная: он мог простоять дольше
        // выданного срока. В тексте это должно читаться как «0h», а не «-5h»
        return Math.max(0L, hours);
    }

    public long getDiscordId() {
        return this.discordId;
    }

    public String getReason() {
        return this.reason;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public Instant getTimeEnd() {
        return this.timeEnd;
    }

    public Instant getLiftedAt() {
        return this.liftedAt;
    }
}
