package com.pocketsage.tournament.bot.commands.close;

import com.pocketsage.tournament.model.Player;
import com.pocketsage.tournament.repository.MatchRepository;
import com.pocketsage.tournament.repository.PlayerRepository;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Старт и финиш игры внутри матча: подмена ников и разводка по голосовым.
 *
 * <p>Развилка по режиму:
 * <ul>
 *   <li><b>обычный матч</b> — команды уже разложены по порядку записи,
 *       достаточно развести игроков по голосовым;</li>
 *   <li><b>immortal draft</b> — клозер сам выбирает состав команд в двух
 *       модалках, и только после этого игроки разводятся.</li>
 * </ul>
 *
 * <p>Подмена ников обратима: исходный ник сохраняется в БД до смены
 * и возвращается в конце матча. Если Steam-ник не задан (/bind), ник
 * не трогаем — иначе игрок потерял бы своё имя до конца матча.
 *
 * <p>Нужные права бота: {@code MANAGE_NICKNAMES} и {@code MOVE_MEMBER}.
 */
public final class MatchGameService {

    private static final Logger log = LoggerFactory.getLogger(MatchGameService.class);

    private final MatchRepository matches;
    private final PlayerRepository players;

    /** Гильдия, где живут клозы: нужна для участников и проверки прав. */
    private final Guild guild;

    public MatchGameService(
        MatchRepository matches,
        PlayerRepository players,
        Guild guild
    ) {
        this.matches = matches;
        this.players = players;
        this.guild = guild;
    }

    /**
     * Начинает игру: подменяет ники и раскидывает игроков по голосовым.
     *
     * <p>Состав должен быть уже разложен по командам: для обычного матча
     * это делает порядок записи, для драфта — модалки клозера.
     */
    public Report startGame(CloseMatch match, VoiceChannel voiceA, VoiceChannel voiceB) {
        if (!match.markPlaying()) {
            return Report.failed("Матч уже не принимает старт.");
        }

        int renamed = 0;
        int moved = 0;
        int skipped = 0;

        for (long discordId : match.participants()) {
            if (applyNickname(match, discordId)) {
                renamed++;
            } else {
                skipped++;
            }
            VoiceChannel target = match.teamOf(discordId) == 'a' ? voiceA : voiceB;
            if (moveTo(discordId, target)) {
                moved++;
            }
        }

        log.info(
            "Матч #{} клоза {} запущен: ников {}, голосовых {}, пропущено {}",
            match.getNumber(),
            match.getCloseId(),
            renamed,
            moved,
            skipped
        );
        return Report.started(match.size(), renamed, moved, skipped);
    }

    /**
     * Возвращает ники участников и переводит их в канал ожидания.
     *
     * <p>Вызывается и при завершении матча, и при его удалении — иначе
     * подменённые ники остались бы навсегда, а игроки — в голосовых команд.
     *
     * @param waiting канал ожидания; если не найден, переводить некуда —
     *               ники всё равно возвращаются
     */
    public Report endGame(CloseMatch match, VoiceChannel waiting) {
        int restored = 0;
        int moved = 0;

        try {
            for (long discordId : match.participants()) {
                // в ожидание возвращаем всех участников, даже если ник
                // не менялся: они сейчас в голосовых командных каналах
                if (moveTo(discordId, waiting)) {
                    moved++;
                }

                Player player = players.findByDiscordId(discordId).orElse(null);
                if (player == null || player.getId() == null) {
                    continue;
                }
                Optional<String> saved = matches.findOriginalNickname(
                    match.getMatchId(),
                    player.getId()
                );
                if (saved.isEmpty()) {
                    continue; // ник не меняли — нечего возвращать
                }
                if (restoreNickname(discordId, saved.get())) {
                    restored++;
                }
            }
            matches.clearOriginalNicknames(match.getMatchId());
        } catch (SQLException e) {
            log.error("Не удалось вернуть ники матча #{}", match.getNumber(), e);
        }

        log.info(
            "Матч #{} клоза {} закончен: ников возвращено {}, в ожидание переведено {}",
            match.getNumber(),
            match.getCloseId(),
            restored,
            moved
        );
        return Report.ended(restored, moved);
    }
    // ===== Ники =====

    /**
     * Меняет ник участника на его Steam-ник.
     *
     * @return true — ник применён; false — Steam-ник не задан или нет прав
     */
    private boolean applyNickname(CloseMatch match, long discordId) {
        try {
            Player player = players.findByDiscordId(discordId).orElse(null);
            if (player == null || player.getId() == null || isBlank(player.getSteamName())) {
                return false; // без /bind подставлять нечего
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                return false; // участник вышел с сервера
            }

            String current = member.getEffectiveName();
            if (player.getSteamName().equals(current)) {
                return false; // ник уже тот же — нечего менять и нечего откатывать
            }

            // запоминаем ДО подмены: иначе вернуть будет нечего
            matches.rememberOriginalNickname(
                match.getMatchId(),
                player.getId(),
                current
            );

            member.modifyNickname(player.getSteamName()).queue(
                null,
                error -> log.warn(
                    "Не удалось сменить ник игрока {} (нет прав MANAGE_NICKNAMES?)",
                    discordId,
                    error
                )
            );
            return true;
        } catch (SQLException e) {
            log.error("Не удалось подменить ник игрока {}", discordId, e);
            return false;
        }
    }

    /** Возвращает участнику сохранённый ник (null — сброс на глобальное имя). */
    private boolean restoreNickname(long discordId, String nickname) {
        Member member = guild.getMemberById(discordId);
        if (member == null) {
            return false;
        }
        member.modifyNickname(nickname).queue(
            null,
            error -> log.warn("Не удалось вернуть ник игрока {}", discordId, error)
        );
        return true;
    }

    /**
     * Переводит игрока в канал ожидания — он записался на матч и ждёт старта.
     *
     * @return true — перевод отправлен; false — канала/участника/прав нет
     */
    public boolean moveToWaiting(long discordId, VoiceChannel waiting) {
        return moveTo(discordId, waiting);
    }

    // ===== Голосовые =====

    /** Переводит участника в указанный голосовой канал. */
    private boolean moveTo(long discordId, VoiceChannel target) {
        if (target == null) {
            return false;
        }
        Member member = guild.getMemberById(discordId);
        if (member == null) {
            return false; // участник вышел с сервера
        }
        if (!guild.getSelfMember().hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            log.warn("Нет прав VOICE_MOVE_OTHERS — развести игроков по голосовым не выйдет");
            return false;
        }
        guild.moveVoiceMember(member, target).queue(
            null,
            error -> log.warn(
                "Не удалось перевести игрока {} в голосовой канал",
                discordId,
                error
            )
        );
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Итог операции: что получилось, а что нет. */
    public record Report(
        boolean ok,
        String message,
        int total,
        int renamed,
        int moved,
        int restored,
        int skipped
    ) {

        static Report started(int total, int renamed, int moved, int skipped) {
            String message = "🎮 Игра началась: участников " +
                total +
                ", ников подменено " +
                renamed +
                ", переведено в голосовые " +
                moved;
            if (skipped > 0) {
                message += ", без Steam-ника: " + skipped;
            }
            return new Report(true, message, total, renamed, moved, 0, skipped);
        }

        static Report ended(int restored, int moved) {
            String message = "🏁 Игра закончена, ников возвращено: " + restored;
            if (moved > 0) {
                message += ", в ожидание переведено: " + moved;
            }
            return new Report(true, message, 0, 0, moved, restored, 0);
        }

        static Report failed(String message) {
            return new Report(false, message, 0, 0, 0, 0, 0);
        }
    }
}
