package com.pocketsage.tournament.model;

import java.time.Instant;

/**
 * Бан игрока в клозах — строка таблицы {@code close_ban}.
 *
 * <p>Срок в часах: администратору нужны «3 часа» и «навсегда», а пересчёт в
 * дни добавлял бы склонения и ошибки округления без пользы.
 *
 * <p>Бан глобальный — он не привязан к клозу и мешает записаться в любой.
 *
 * <p><b>Бессрочность — это {@code timeEnd == null}.</b> Истёкший срок и
 * бессрочность выглядят по-разному, поэтому «до 1970 года» в качестве
 * обозначения вечности не используется.
 */
public class CloseBan {

    /** Бан без срока — до снятия через /close_unban. */
    public static final int FOREVER = 0;

    private Long id; // null до сохранения в БД
    private long discordId; // к кому применён, NOT NULL
    private String reason; // NOT NULL
    private int durationHours; // 0 = навсегда
    private Instant timeStart;
    private Instant timeEnd; // null = навсегда
    private boolean lifted;
    private Long liftedBy;
    private Instant liftedAt;

    public CloseBan(
        Long id,
        long discordId,
        String reason,
        int durationHours,
        Instant timeStart,
        Instant timeEnd,
        boolean lifted,
        Long liftedBy,
        Instant liftedAt
    ) {
        this.id = id;
        this.discordId = discordId;
        this.reason = reason;
        this.durationHours = durationHours;
        this.timeStart = timeStart;
        this.timeEnd = timeEnd;
        this.lifted = lifted;
        this.liftedBy = liftedBy;
        this.liftedAt = liftedAt;
    }

    /** Проставляется репозиторием после INSERT — вручную не вызывается. */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Бан действует сейчас: не снят и либо бессрочный, либо срок не прошёл.
     *
     * <p>Время передаётся аргументом, а не берётся внутри — так проверку можно
     * прогнать на любой момент в тестах.
     */
    public boolean isActiveAt(Instant now) {
        if (lifted) {
            return false;
        }
        return timeEnd == null || timeEnd.isAfter(now);
    }

    /** Бессрочный бан — срока нет вовсе. */
    public boolean isForever() {
        return timeEnd == null;
    }

    public Long getId() {
        return this.id;
    }

    public long getDiscordId() {
        return this.discordId;
    }

    public String getReason() {
        return this.reason;
    }

    public int getDurationHours() {
        return this.durationHours;
    }

    public Instant getTimeStart() {
        return this.timeStart;
    }

    public Instant getTimeEnd() {
        return this.timeEnd;
    }

    public boolean isLifted() {
        return this.lifted;
    }

    public Long getLiftedBy() {
        return this.liftedBy;
    }

    public Instant getLiftedAt() {
        return this.liftedAt;
    }
}
