package com.pocketsage.tournament.bot.commands.closeban;

import com.pocketsage.tournament.model.CloseBan;
import com.pocketsage.tournament.repository.CloseBanRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Логика банов: выдать, снять, показать историю и ответить на главный вопрос
 * «можно ли этому игроку записаться».
 *
 * <p>Всё состояние в БД, в памяти ничего нет — после перезапуска бота бан
 * продолжает действовать.
 *
 * <p>Тексты для Discord собраны здесь же, а не в обработчике: их видно в тестах
 * без поднятия JDA.
 */
public class CloseBanService {

    private static final Logger log = LoggerFactory.getLogger(CloseBanService.class);

    /**
     * Срок в часах. Константа живёт здесь, а не в модели: в БД «навсегда»
     * — это {@code time_end IS NULL}, отдельного значения для бессрочного
     * бана не хранится.
     */
    public static final int FOREVER = 0;

    /**
     * Разумный предел: без него «99999» превратится в дату через 11 лет,
     * а такую опечатку потом не разобрать.
     */
    public static final int MAX_HOURS = 24 * 365;

    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final CloseBanRepository bans;

    public CloseBanService() {
        this(new CloseBanRepository());
    }

    /** Конструктор с готовым репозиторием — нужен тестам. */
    public CloseBanService(CloseBanRepository bans) {
        this.bans = bans;
    }

    /** Выдаёт бан по Discord id. */
    public CloseBan issueBan(
        long playerDiscordId,
        String reason,
        int durationHours
    ) throws SQLException {
        return bans.issue(playerDiscordId, normalizeReason(reason), durationHours);
    }

    /** Снимает бан по Discord id игрока. false — бана не было. */
    public boolean liftBan(long playerDiscordId) throws SQLException {
        return bans.lift(playerDiscordId) > 0;
    }

    /** История банов игрока — для /close_ban_show. */
    public List<CloseBan> history(long playerDiscordId) throws SQLException {
        return bans.findHistory(playerDiscordId);
    }

    /**
     * Действующий бан игрока. Пусто — можно записываться.
     *
     * <p>При ошибке БД возвращаем пусто и пишем в лог: лучше один игрок пройдёт
     * мимо бана, чем заблокируются все из-за недоступной базы.
     */
    public Optional<CloseBan> findActiveBan(long discordId) {
        try {
            return bans.findActive(discordId);
        } catch (SQLException e) {
            log.error("Не удалось проверить бан игрока (discord_id={})", discordId, e);
            return Optional.empty();
        }
    }

    /**
     * Разбирает введённый срок в часах.
     *
     * <p>Пусто или 0 — навсегда: игрок не обязан ничего вводить, а бессрочный
     * бан — самый частый случай при серьёзном нарушении.
     */
    public static int parseHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return FOREVER;
        }
        int hours;
        try {
            hours = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Срок должен быть числом часов (например: 3).");
        }
        if (hours < 0) {
            throw new IllegalArgumentException("Срок не может быть отрицательным.");
        }
        if (hours == 0) {
            return FOREVER;
        }
        if (hours > MAX_HOURS) {
            throw new IllegalArgumentException(
                "Срок не может быть больше " + MAX_HOURS + " часов.");
        }
        return hours;
    }

    /** Причина NOT NULL: пустую строку превращаем в осмысленную. */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "без причины";
        }
        // причина уходит в сообщения Discord, поэтому длинный текст режем:
        // иначе сообщение упрётся в лимит и всё упадёт молча
        String trimmed = reason.strip();
        return trimmed.length() <= MAX_REASON_LENGTH
            ? trimmed
            : trimmed.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    private static final int MAX_REASON_LENGTH = 900;

    /**
     * Срок в виде «24h» — намеренно без склонений.
     *
     * <p>Русские формы («2 часа», «5 часов») требуют отдельной функции склонения
     * на каждое число и легко ломаются на 11/12/14 и 21/22/24. Здесь это не
     * нужно: срок всегда в часах, а «24h» читается однозначно и переводится
     * в дату таймстампом Discord.
     */
    public static String formatHours(int hours) {
        return hours == FOREVER ? "навсегда" : hours + "h";
    }

    /** Отказ в записи — видит забаненный игрок. */
    public static String formatRejection(CloseBan ban) {
        return "🚫 **Вы забанены в клозах**\n"
            + "Причина: " + ban.getReason() + "\n"
            + "Срок: " + formatHours((int) ban.durationHours()) + "\n"
            + formatExpiry(ban)
            + "\nЕсли считаете это ошибкой — напишите администрации.";
    }

    /** Подтверждение выдачи — видит выдавший. */
    public static String formatIssued(CloseBan ban) {
        return "🔨 Бан выдан\n"
            + "Срок: " + formatHours((int) ban.durationHours()) + "\n"
            + formatExpiry(ban)
            + "\nПричина: " + ban.getReason();
    }

    /** Строка про окончание: навсегда, конкретное время или «срок вышел». */
    private static String formatExpiry(CloseBan ban) {
        if (ban.isForever()) {
            return "Действует: **навсегда** (до снятия через /close_unban)";
        }
        return "До: <t:" + ban.getTimeEnd().getEpochSecond() + ":F> (<t:"
            + ban.getTimeEnd().getEpochSecond() + ":R>)";
    }

    /** История банов — видит выдавший. */
    public static String formatHistory(List<CloseBan> history) {
        if (history.isEmpty()) {
            return "📋 Банов нет.";
        }
        StringBuilder text = new StringBuilder("📋 История банов (" + history.size() + "):\n");
        int shown = Math.min(history.size(), 10);
        for (int i = 0; i < shown; i++) {
            CloseBan ban = history.get(i);
            text.append("\n**#").append(i + 1).append(' ');
            if (ban.isLifted()) {
                text.append("снят ").append(DATE_FORMAT.format(ban.getLiftedAt()));
            } else if (ban.isActiveAt(Instant.now())) {
                text.append("**АКТИВЕН** · ").append(formatHours((int) ban.durationHours()));
            } else {
                text.append("истёк · ").append(formatHours((int) ban.durationHours()));
            }
            text.append("\nВыдан: ").append(DATE_FORMAT.format(ban.getCreatedAt()));
            text.append("\nПричина: ").append(ban.getReason());
        }
        if (history.size() > shown) {
            text.append("\n…и ещё ").append(history.size() - shown);
        }
        return text.toString();
    }
}
