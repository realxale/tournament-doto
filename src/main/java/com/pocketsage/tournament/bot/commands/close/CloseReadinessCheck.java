package com.pocketsage.tournament.bot.commands.close;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Проверка готовности матча.
 *
 * <p>Живёт внутри матча, а не клоза: кто записан в конкретный матч, тот и
 * подтверждает готовность. Проверка запускается кнопкой «Проверка
 * готовности» в управлении матчем. Кто не нажал «Я готов» за {@link #WINDOW}
 * — выписывается из состава этого матча, карточка перерисовывается.
 *
 * <p>Подтвердить готовность можно, только находясь в голосовом канале
 * «ожидание»: Discord отдаёт состав участников голосового из кэша, поэтому
 * проверка сводится к тому, есть ли id участника в списке участников
 * канала. Так бот сам отсеивает тех, кто записался и сразу вышел.
 */
public final class CloseReadinessCheck {

    /** Сколько ждём подтверждения перед авто-выписью. */
    public static final Duration WINDOW = Duration.ofMinutes(3);

    private static final Logger log = LoggerFactory.getLogger(CloseReadinessCheck.class);
    private static final Map<Long, CloseReadinessCheck> ACTIVE = new ConcurrentHashMap<>();

    private final JDA jda;
    private final CloseMatch match;
    private final TextChannel signupChannel;
    private final long messageId;
    private final long deadlineEpochSeconds;
    private final ScheduledFuture<?> finishTask;

    /**
     * Канал «ожидание», в котором игроки обязаны находиться.
     *
     * <p>{@code null} — канал не найден (например, клоз создан до появления
     * канала ожидания). Тогда проверка голоса отключается: подтверждает кто
     * угодно из записанных, как и раньше.
     */
    private final VoiceChannel waitingVoice;

    private CloseReadinessCheck(
        JDA jda,
        CloseMatch match,
        TextChannel signupChannel,
        long messageId,
        long deadlineEpochSeconds,
        ScheduledFuture<?> finishTask,
        VoiceChannel waitingVoice
    ) {
        this.jda = jda;
        this.match = match;
        this.signupChannel = signupChannel;
        this.messageId = messageId;
        this.deadlineEpochSeconds = deadlineEpochSeconds;
        this.finishTask = finishTask;
        this.waitingVoice = waitingVoice;
    }

    /**
     * Запускает проверку для матча: сбрасывает прошлые подтверждения и ставит
     * таймер. Сообщение уходит в «запись» — туда же, где карточка матча.
     *
     * @param waitingVoice канал «ожидание» для проверки присутствия; может
     *                      быть {@code null}, тогда проверки голоса нет
     */
    public static void start(
        CloseMatch match,
        TextChannel signupChannel,
        VoiceChannel waitingVoice,
        ScheduledExecutorService scheduler
    ) {
        match.startReadinessCheck();

        long matchId = match.getMatchId();
        long deadline = Instant.now().plus(WINDOW).getEpochSecond();

        signupChannel
            .sendMessage(match.renderReadiness(deadline))
            .setComponents(CloseRegistrations.readyButtons(match.getCloseCategoryId()))
            .queue(
                message -> {
                    CloseReadinessCheck check = new CloseReadinessCheck(
                        signupChannel.getJDA(),
                        match,
                        signupChannel,
                        message.getIdLong(),
                        deadline,
                        scheduler.schedule(
                            () -> finish(matchId),
                            WINDOW.toMillis(),
                            TimeUnit.MILLISECONDS
                        ),
                        waitingVoice
                    );
                    CloseReadinessCheck previous = ACTIVE.put(matchId, check);
                    if (previous != null) {
                        previous.finishTask.cancel(false);
                    }
                },
                error ->
                    log.error(
                        "Не удалось отправить проверку готовности матча #{}",
                        match.getNumber(),
                        error
                    )
            );
    }

    public static boolean isActive(long matchId) {
        return ACTIVE.containsKey(matchId);
    }

    /**
     * Находится ли игрок в голосовом канале «ожидание».
     *
     * <p>Список участников канала JDA берёт из кэша, то есть из данных о
     * голосовых состояниях, которые Discord присылает сам. Поэтому проверка
     * не требует ни одного запроса к API — просто поиск id в списке.
     *
     * <p>Канала ожидания нет — возвращаем {@code true}: проверять нечего,
     * иначе клоз, созданный до появления канала, был бы заблокирован целиком.
     *
     * @return true — игрок в ожидании либо проверка голоса отключена
     */
    public static boolean isInWaitingVoice(CloseMatch match, long discordId) {
        CloseReadinessCheck check = ACTIVE.get(match.getMatchId());
        if (check == null || check.waitingVoice == null) {
            return true;
        }
        return isInVoice(check.waitingVoice, discordId);
    }

    /**
     * Проверка присутствия по самому каналу — используется и при нажатии
     * «Я готов», и при авто-выписке в конце окна.
     */
    private static boolean isInVoice(VoiceChannel voice, long discordId) {
        return voice
            .getMembers()
            .stream()
            .anyMatch(member -> member.getIdLong() == discordId);
    }

    /** Перерисовывает актуальное сообщение проверки (после нажатия «Я готов»). */
    public static void refresh(CloseMatch match) {
        CloseReadinessCheck check = ACTIVE.get(match.getMatchId());
        if (check == null) {
            return;
        }
        check.edit(
            check.match.renderReadiness(check.deadlineEpochSeconds),
            CloseRegistrations.readyButtons(check.match.getCloseCategoryId())
        );
    }

    /** Снимает проверку, никого не выписывая (например, матч удалили). */
    public static void cancel(long matchId) {
        CloseReadinessCheck check = ACTIVE.remove(matchId);
        if (check != null) {
            check.finishTask.cancel(false);
        }
    }

    /** Конец окна: выписываем тех, кто не подтвердил или вышел из ожидания. */
    private static void finish(long matchId) {
        CloseReadinessCheck check = ACTIVE.remove(matchId);
        if (check == null) {
            return;
        }

        // кто записан, но сейчас не в канале ожидания — тоже неявка
        List<Long> absentFromVoice = check.waitingVoice == null
            ? List.of()
            : check.match
                .participants()
                .stream()
                .filter(discordId -> !isInVoice(check.waitingVoice, discordId))
                .toList();
        for (long discordId : absentFromVoice) {
            check.match.signOff(discordId);
        }

        // выписываем всех, кто не подтвердил готовность
        List<Long> kicked = new ArrayList<>(check.match.signOffNotReady());
        // signOff мог уже убрать вышедших из ожидания — не дублируем их в отчёте
        absentFromVoice.stream().filter(id -> !kicked.contains(id)).forEach(kicked::add);

        // 1. закрываем сообщение проверки (кнопка «Я готов» больше не нужна)
        check.edit(check.match.renderReadinessResult(kicked), null);

        // 2. обновляем карточку матча в «запись»
        CloseRegistrations.refreshSignupCard(check.jda, check.match);

        log.info(
            "Проверка готовности матча #{} завершена, выписано: {} (не в ожидании: {})",
            check.match.getNumber(),
            kicked.size(),
            absentFromVoice.size()
        );
    }

    /** Правка сообщения проверки; row == null — убрать кнопки. */
    private void edit(String text, ActionRow row) {
        MessageEditAction action = signupChannel.editMessageById(messageId, text);
        if (row == null) {
            action.setComponents();
        } else {
            action.setComponents(row);
        }
        action.queue(
            null,
            error ->
                log.error(
                    "Не удалось обновить проверку готовности матча #{}",
                    match.getNumber(),
                    error
                )
        );
    }
}
