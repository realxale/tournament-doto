package com.pocketsage.tournament.bot.commands.close;

import com.pocketsage.tournament.bot.commands.closeban.CloseBanService;
import com.pocketsage.tournament.model.CloseBan;
import com.pocketsage.tournament.repository.CloseRepository;
import com.pocketsage.tournament.repository.MatchRepository;
import com.pocketsage.tournament.repository.PlayerRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработка кнопок клоза.
 *
 * <p>В «запись» — «Записаться» / «Отписаться» и «Я готов»: действия игрока
 * внутри конкретного матча. В «управление» — «Создать матч» / «Начать игру» / «Удалить матч» /
 * «Завершить матч» и «Удалить клоз»: действия клозера или модератора, они
 * меняют состояние клоза в памяти и в БД.
 */
public class CloseRegistrationHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CloseRegistrationHandler.class);

    /** Discord не удаляет больше 100 сообщений за один запрос. */
    private static final int BULK_DELETE_LIMIT = 100;

    private final CloseRepository closes;
    private final MatchRepository matches;
    private final PlayerRepository players;
    private final ScheduledExecutorService scheduler;

    public CloseRegistrationHandler() {
        this(
            new CloseRepository(),
            new MatchRepository(),
            new PlayerRepository(),
            defaultScheduler()
        );
    }

    /** Конструктор с готовыми зависимостями — нужен тестам. */
    public CloseRegistrationHandler(
        CloseRepository closes,
        MatchRepository matches,
        PlayerRepository players,
        ScheduledExecutorService scheduler
    ) {
        this.closes = closes;
        this.matches = matches;
        this.players = players;
        this.scheduler = scheduler;
        this.closeBanService = new CloseBanService();
    }

    /**
     * Проверка банов перед записью и стартом игры.
     *
     * <p>Создаётся сам, а не принимается снаружи: он завязан на глобальный
     * реестр БД и не зависит от состояния конкретного клоза. Тестам, которым
     * понадобится подмена, конструктор можно расширить.
     */
    private final CloseBanService closeBanService;

    /** Один поток на весь процесс: таймеры проверок готовности. */
    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "close-readiness");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        char team = DraftModal.teamOf(event.getModalId());
        if (team == 0) {
            return; // модалка не наша (например /bind или создание клоза)
        }
        handleDraftSubmit(event, team);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String action = actionOf(event.getComponentId());
        if (action == null) {
            return; // кнопка не наша
        }

        try {
            dispatch(event, action);
        } catch (RuntimeException e) {
            // без этого обработчик молча умирает: игрок видит «ничего не
            // произошло», а в логе нет ни слова — и непонятно, где чинить
            log.error(
                "Ошибка при обработке кнопки {} (клоз {}, пользователь {})",
                action,
                categoryIdOf(event.getComponentId()),
                event.getUser().getId(),
                e
            );
            replyTo(event, "❌ Не удалось выполнить действие, попробуй ещё раз.");
        }
    }

    private void dispatch(ButtonInteractionEvent event, String action) {
        String categoryId = categoryIdOf(event.getComponentId());
        CloseRegistration close = CloseRegistrations.get(categoryId);
        if (close == null) {
            event.reply("❌ Этот клоз больше не активен.")
                .setEphemeral(true)
                .queue();
            return;
        }

        switch (action) {
            // '\0' — без выбора команды: immortal draft, там решает клозер
            case CloseRegistrations.ACTION_SIGNUP -> handleSignup(event, close, '\0');
            case CloseRegistrations.ACTION_SIGNUP_A -> handleSignup(event, close, 'a');
            case CloseRegistrations.ACTION_SIGNUP_B -> handleSignup(event, close, 'b');
            case CloseRegistrations.ACTION_SIGNOFF -> handleSignOff(event, close);
            case CloseRegistrations.ACTION_READY -> handleReady(event, close);
            case CloseRegistrations.ACTION_READY_START -> handleReadyStart(event, close);
            case CloseRegistrations.ACTION_MATCH_START -> handleMatchStart(event, close);
            case CloseRegistrations.ACTION_MATCH_DELETE -> handleMatchDelete(event, close);
            case CloseRegistrations.ACTION_MATCH_FINISH -> handleMatchFinish(event, close);
            case CloseRegistrations.ACTION_CLOSE_DELETE -> handleCloseDelete(event, close);
            case CloseRegistrations.ACTION_GAME_START -> handleGameStart(event, close);
            case CloseRegistrations.ACTION_DRAFT_A -> handleDraft(event, close, 'a');
            default -> {
                // неизвестное действие нашего типа — игнорируем
            }
        }
    }

    // ===== Игрок в матче =====

    /**
 * Запись игрока в матч.
     *
     * @param team 'a' или 'b' — выбранная команда; 0 — без выбора (immortal
     *            draft, там команды формирует клозер)
     */
    private void handleSignup(
        ButtonInteractionEvent event,
        CloseRegistration close,
        char team
    ) {
        CloseMatch match = requireCollectingMatch(event, close);
        if (match == null) {
            return;
        }

        long discordId = event.getUser().getIdLong();

        // Проверяем бан до любых изменений: игрок мог записаться до бана,
        // и тогда он всё равно не должен попасть на игру
        Optional<CloseBan> ban = closeBanService.findActiveBan(discordId);
        if (ban.isPresent()) {
            event.reply(CloseBanService.formatRejection(ban.get()))
                .setEphemeral(true)
                .queue();
            return;
        }

        boolean signed = team == 0
            ? match.signUp(discordId)
            : match.signUpToTeam(discordId, team);
        if (!signed) {
            event.reply(rejectionReason(match, discordId, team))
                .setEphemeral(true)
                .queue();
            return;
        }

        // карточку правим сразу: и Discord даёт на всё взаимодействие 3 секунды,
        // и БД/перевод в голосовой ниже могут упасть — правка состава важнее
        refreshSignupCard(event, match);
        // «Проверка готовности» оживает, как только в матче есть игрок
        refreshMatchControl(event, close);

        // дальше — вспомогательные шаги, каждый изолирован: их сбой не должен
        // выглядеть так, будто запись не прошла
        persistParticipant(event, match, discordId, true);
        // записался — ждём клозера в голосовом «ожидание»
        moveToWaiting(event, close, discordId);
    }

    /** Причина отказа в записи — показываем эфемерно, карточку не трогаем. */
    private static String rejectionReason(CloseMatch match, long discordId, char team) {
        if (match.isSignedUp(discordId)) {
            return "⚠️ Вы уже записаны в этот матч.";
        }
        if (!match.isCollecting()) {
            return "❌ Матч уже не принимает запись.";
        }
        if (team != 0 && match.isTeamFull(team)) {
            return (
                "❌ Команда " +
                (team == 'a' ? "A" : "B") +
                " уже собрана (" +
                CloseMatch.TEAM_SIZE +
                "/" +
                CloseMatch.TEAM_SIZE +
                "). Попробуй другую."
            );
        }
        if (match.isFull()) {
            return "❌ Все " + CloseMatch.SLOTS + " мест заняты.";
        }
        return "❌ Не удалось записаться.";
    }

    private void handleSignOff(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        CloseMatch match = close.getCurrentMatch();
        if (match == null) {
            event.reply("❌ Активного матча нет.").setEphemeral(true).queue();
            return;
        }

        long discordId = event.getUser().getIdLong();
        if (!match.signOff(discordId)) {
            event.reply("⚠️ Вы не записаны в этот матч.")
                .setEphemeral(true)
                .queue();
            return;
        }

        refreshSignupCard(
            event,
            match,
            "✅ Отписался от матча #" + match.getNumber() + "."
        );
        // могло стать пусто — тогда проверка готовности гаснет
        refreshMatchControl(event, close);

        persistParticipant(event, match, discordId, false);
    }

    /**
     * «Я готов»: засчитываем отклик и перерисовываем актуальное сообщение
     * проверки. Сообщение, по которому кликнули, может быть старым (проверку
     * запускали повторно), поэтому текст правим по id, а не через это нажатие.
     */
    private void handleReady(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        CloseMatch match = close.getCurrentMatch();
        if (match == null || !CloseReadinessCheck.isActive(match.getMatchId())) {
            event.reply("⚠️ Проверка готовности сейчас не запущена.")
                .setEphemeral(true)
                .queue();
            return;
        }

        long userId = event.getUser().getIdLong();
        if (match.isReady(userId)) {
            event.reply("⚠️ Вы уже подтвердили готовность.")
                .setEphemeral(true)
                .queue();
            return;
        }
        if (!match.isSignedUp(userId)) {
            event.reply("⚠️ Вы не записаны в этот матч.")
                .setEphemeral(true)
                .queue();
            return;
        }
        // подтверждать можно только из голосового «ожидание»: это отсекает
        // тех, кто записался с телефона и сразу вышел
        if (!CloseReadinessCheck.isInWaitingVoice(match, userId)) {
            event
                .reply(
                    "🔇 Подключись к голосовому каналу **ожидание** и подтверди готовность оттуда."
                )
                .setEphemeral(true)
                .queue();
            return;
        }
        match.confirmReady(userId);

        event.deferEdit().queue(
            null,
            error ->
                log.error("Не удалось подтвердить готовность игрока {}", userId, error)
        );
        CloseReadinessCheck.refresh(match);
    }

    /** «Проверка готовности»: запускает окно подтверждений по составу матча. */
    private void handleReadyStart(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = requireCollectingMatch(event, close);
        if (match == null) {
            return;
        }
        if (match.size() == 0) {
            event.reply("⚠️ В матче пока никто не записан.")
                .setEphemeral(true)
                .queue();
            return;
        }

        TextChannel signupChannel = findSignupChannel(event, close);
        if (signupChannel == null) {
            event.reply("❌ Не найден канал «запись».").setEphemeral(true).queue();
            return;
        }

        // канал ожидания нужен для проверки присутствия; если его нет,
        // проверка голоса отключится, а не сломает клоз
        VoiceChannel waiting = findWaitingVoice(event, close);

        CloseReadinessCheck.start(match, signupChannel, waiting, scheduler);
        String hint = waiting == null
            ? "\n⚠️ Канал «ожидание» не найден — проверка присутствия в голосе отключена."
            : "\n🔊 Подтвердить можно только находясь в " + waiting.getAsMention() + ".";
        event
            .reply(
                "⏱️ Проверка готовности запущена, окно " +
                CloseReadinessCheck.WINDOW.toMinutes() +
                " мин." +
                hint
            )
            .queue();
    }

    // ===== Управление матчем =====

    /** «Создать матч»: публикует карточку поиска игроков в «запись». */
    private void handleMatchStart(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match != null && match.isAnnounced()) {
            event.reply("⚠️ Матч уже идёт.").setEphemeral(true).queue();
            return;
        }

        if (match == null) {
            // предыдущий матч доигран или удалён — заводим следующий
            try {
                match = createMatch(close);
            } catch (SQLException e) {
                log.error("Не удалось создать матч клоза {}", close.getCloseId(), e);
                event.reply("❌ Ошибка БД: " + e.getMessage()).setEphemeral(true).queue();
                return;
            }
            close.setCurrentMatch(match);
        }

        CloseMatch started = match;
        deferThenReply(event, "🔎 Матч #" + match.getNumber() + " — поиск игроков объявлен.");
        announceMatch(event, close, started);
    }

    /** «Удалить матч»: убирает карточку и помечает матч CANCELLED в БД. */
    private void handleMatchDelete(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match == null) {
            event.reply("⚠️ Активного матча нет.").setEphemeral(true).queue();
            return;
        }

        CloseReadinessCheck.cancel(match.getMatchId());
        // ники возвращаем ДО отмены в БД: возврат читает сохранённые значения
        endGame(event, close, match);
        try {
            matches.cancel(match.getMatchId());
        } catch (SQLException e) {
            log.error("Не удалось отменить матч {}", match.getMatchId(), e);
            event.reply("❌ Ошибка БД: " + e.getMessage()).setEphemeral(true).queue();
            return;
        }

        match.markCancelled();
        close.clearCurrentMatch();
        refreshMatchControl(event, close);
        // чистим весь канал «запись»: не только карточку матча, но и
        // сообщения проверки готовности — матч удалён, они больше не нужны
        purgeSignupChannel(event, close, match.getNumber());
        event.reply("🗑️ Матч #" + match.getNumber() + " удалён.").queue();
    }

    /**
     * Удаляет все сообщения канала «запись».
     *
     * <p>Раньше удалялась только карточка матча, а сообщения проверки
     * готовности оставались висеть. Теперь чистится весь канал.
     *
     * <p>Обход истории идёт в отдельном потоке: {@code stream()} тянет все
     * страницы и блокирует вызывающий, а обработчик кнопок не должен висеть.
     */
    private void purgeSignupChannel(
        ButtonInteractionEvent event,
        CloseRegistration close,
        int matchNumber
    ) {
        TextChannel signup = findSignupChannel(event, close);
        if (signup == null) {
            log.warn(
                "Канал «запись» клоза {} не найден — чистить нечего",
                close.getCategoryId()
            );
            return;
        }

        CompletableFuture.runAsync(() -> {
            List<String> ids = signup
                .getIterableHistory()
                .stream()
                .map(net.dv8tion.jda.api.entities.Message::getId)
                .toList();
            if (ids.isEmpty()) {
                return;
            }
            log.info(
                "Матч #{}: удаляем {} сообщений из «запись»",
                matchNumber,
                ids.size()
            );
            deleteInBatches(
                signup,
                ids,
                0,
                error ->
                    log.error(
                        "Не удалось удалить все сообщения в «запись» матча #{}: {}",
                        matchNumber,
                        error
                    )
            );
        });
    }

    /**
     * Удаляет сообщения пачками по {@link #BULK_DELETE_LIMIT}, рекурсивно.
     *
     * <p>Пачки идут строго последовательно: Discord жёстко лимитирует
     * массовое удаление, параллельные запросы дают 429.
     *
     * <p>Ждём завершения <em>всей</em> пачки, иначе следующая стартовала бы
     * на каждое сообщение отдельно.
     *
     * <p>Продолжаем и после ошибки: Discord не удаляет сообщения старше
     * 14 дней, и одно такое сообщение не должно останавливать уборку.
     */
    private void deleteInBatches(
        TextChannel channel,
        List<String> ids,
        int from,
        java.util.function.Consumer<Throwable> onError
    ) {
        if (from >= ids.size()) {
            return;
        }
        int to = Math.min(from + BULK_DELETE_LIMIT, ids.size());
        List<CompletableFuture<Void>> batch = channel
            .purgeMessagesById(ids.subList(from, to))
            .stream()
            .map(future -> (CompletableFuture<Void>) future)
            .toList();

        CompletableFuture
            .allOf(batch.toArray(new CompletableFuture[0]))
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    onError.accept(error);
                }
                deleteInBatches(channel, ids, to, onError);
            });
    }

    /** «Завершить матч»: фиксирует состав, время конца и статус FINISHED. */
    private void handleMatchFinish(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match == null || !match.isCollecting()) {
            event.reply("⚠️ Активного матча нет.").setEphemeral(true).queue();
            return;
        }

        int players = match.size();
        // ники возвращаем ДО завершения в БД
        endGame(event, close, match);
        try {
            // победитель ещё неизвестен, поэтому result остаётся NULL
            matches.finish(match.getMatchId(), null);
        } catch (SQLException e) {
            log.error("Не удалось завершить матч {}", match.getMatchId(), e);
            event.reply("❌ Ошибка БД: " + e.getMessage()).setEphemeral(true).queue();
            return;
        }

        match.markFinished();
        close.clearCurrentMatch();
        refreshMatchControl(event, close);
        lockSignupCard(event, match);
        event.reply(
            "🏁 Матч #" + match.getNumber() + " завершён, участников: " + players + "."
        ).queue();
    }

    // ===== Управление клозом =====

    /** «Удалить клоз»: CANCELLED в БД плюс удаление категории с каналами. */
    private void handleCloseDelete(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        Guild guild = event.getGuild();
        Category category =
            guild == null ? null : guild.getCategoryById(close.getCategoryId());
        if (category == null) {
            event
                .reply("❌ Категория клоза не найдена — возможно, каналы уже удалены.")
                .setEphemeral(true)
                .queue();
            CloseRegistrations.remove(close.getCategoryId());
            return;
        }

        // без MANAGE_CHANNEL Discord отклонит удаление; лучше сказать сразу,
        // чем молча отметить клоз закрытым и оставить каналы навсегда
        if (!guild.getSelfMember().hasPermission(net.dv8tion.jda.api.Permission.MANAGE_CHANNEL)) {
            event
                .reply(
                    "❌ У бота нет права **MANAGE_CHANNEL** — каналы удалить не могу.\n" +
                    "Выдай право роли бота и попробуй снова."
                )
                .setEphemeral(true)
                .queue();
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match != null) {
            CloseReadinessCheck.cancel(match.getMatchId());
            // ники возвращаем: иначе подмена осталась бы навсегда
            endGame(event, close, match);
            try {
                matches.cancel(match.getMatchId());
            } catch (SQLException e) {
                log.error("Не удалось отменить матч {}", match.getMatchId(), e);
            }
        }

        try {
            closes.cancel(close.getCloseId());
        } catch (SQLException e) {
            log.error("Не удалось закрыть клоз {}", close.getCloseId(), e);
            event.reply("❌ Ошибка БД: " + e.getMessage()).setEphemeral(true).queue();
            return;
        }

        // Удаляем каналы и категорию. Из реестра убираем клоз только после
        // успеха — иначе повторное нажатие было бы невозможно, а каналы
        // остались бы навсегда.
        event
            .reply("🚫 Закрываю клоз и удаляю каналы...")
            .setEphemeral(true)
            .queue();

        deleteCategory(close, category);
    }

    /**
     * Удаляет каналы категории и саму категорию.
     *
     * <p>Каналы удаляем по очереди, а не одним запросом: так при сбое видно,
     * что именно не удалилось, и остатки можно убрать вручную. Клоз убираем
     * из реестра только после успеха — иначе повторное нажатие было бы
     * невозможно, а каналы остались бы навсегда.
     */
    private void deleteCategory(CloseRegistration close, Category category) {
        String categoryId = close.getCategoryId();
        deleteChannelsThenCategory(category, category.getChannels(), categoryId, 0);
    }

    /** Удаляет каналы по очереди; после последнего — саму категорию. */
    private void deleteChannelsThenCategory(
        Category category,
        List<? extends GuildChannel> channels,
        String categoryId,
        int index
    ) {
        if (index >= channels.size()) {
            deleteCategoryItself(category, categoryId);
            return;
        }
        channels
            .get(index)
            .delete()
            .reason("Клоз удалён")
            .queue(
                done ->
                    deleteChannelsThenCategory(
                        category,
                        channels,
                        categoryId,
                        index + 1
                    ),
                error -> {
                    log.error(
                        "Не удалось удалить канал {} клоза {}",
                        channels.get(index).getName(),
                        categoryId,
                        error
                    );
                    // продолжаем: остальные каналы и категорию тоже надо убрать
                    deleteChannelsThenCategory(
                        category,
                        channels,
                        categoryId,
                        index + 1
                    );
                }
            );
    }

    private void deleteCategoryItself(Category category, String categoryId) {
        category
            .delete()
            .reason("Клоз удалён")
            .queue(
                deleted -> {
                    CloseRegistrations.remove(categoryId);
                    log.info("Клоз {} удалён вместе с каналами", categoryId);
                },
                error -> {
                    log.error("Не удалось удалить категорию клоза {}", categoryId, error);
                    notifyChannelsLeft(category, true);
                }
            );
    }

    /**
     * Сообщает в «управление», что каналы удалить не вышло.
     *
     * <p>Само сообщение должно уйти ДО удаления каналов, поэтому идём в
     * «управление» по имени. Если и он недоступен — молча пишем в лог:
     * сообщить всё равно некуда.
     */
    private void notifyChannelsLeft(Category category, boolean channelsDeleted) {
        List<String> names = category
            .getChannels()
            .stream()
            .map(channel -> "#" + channel.getName())
            .toList();
        String text = channelsDeleted
            ? "⚠️ Каналы удалены, но категория `" +
            category.getName() +
            "` осталась — удали её вручную."
            : "⚠️ Не удалось удалить каналы клоза: " +
            String.join(", ", names) +
            ". Удалите их вручную.";

        TextChannel control = category
            .getTextChannels()
            .stream()
            .filter(channel -> channel.getName().equals("управление"))
            .findFirst()
            .orElse(null);
        if (control == null) {
            log.warn("Не удалось удалить каналы клоза {}: {}", category.getId(), names);
            return;
        }
        control.sendMessage(text).queue(
            null,
            error ->
                log.error(
                    "Не удалось предупредить о незакрытых каналах клоза {}",
                    category.getId(),
                    error
                )
        );
    }
// ===== Старт игры =====

    /**
     * «Начать игру»: подменяет ники и раскидывает игроков по голосовым.
     *
     * <p>Развилка по режиму:
     * <ul>
     *   <li>immortal draft — клозеру уходит сообщение с двумя кнопками,
     *       в модалках он выбирает состав каждой команды, и только после
     *       второго выбора игроки разводятся по голосовым;</li>
     *   <li>обычный матч — команды уже разложены по порядку записи,
     *       можно сразу разводить.</li>
     * </ul>
     */
    private void handleGameStart(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match == null || !match.isCollecting()) {
            event.reply("❌ Активного матча нет.").setEphemeral(true).queue();
            return;
        }
        if (match.size() < CloseMatch.SLOTS) {
            event
                .reply(
                    "⚠️ Собрано только " +
                    match.size() +
                    " из " +
                    CloseMatch.SLOTS +
                    " — начать игру пока рано."
                )
                .setEphemeral(true)
                .queue();
            return;
        }

        if (close.isImmortalDraft()) {
            askDraftTeams(event, match);
        } else {
            beginGame(event, close, match);
        }
    }

    /** Immortal draft: предлагает клозеру выбрать состав обеих команд. */
    private void askDraftTeams(ButtonInteractionEvent event, CloseMatch match) {
        event
            .reply(
                """
                🃏 **Immortal draft — матч #%d**

                Выберите состав команд: сначала команду A, затем B.
                Затем игроки будут разведены по голосовым каналам,
                а ники подменены на Steam-ники.
                """.formatted(match.getNumber())
            )
            .setEphemeral(true)
            .setComponents(CloseRegistrations.draftButtons(match))
            .queue();
    }

    /** Открывает модалку выбора состава одной команды. */
    private void handleDraft(
        ButtonInteractionEvent event,
        CloseRegistration close,
        char team
    ) {
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match == null || !match.isCollecting()) {
            event.reply("❌ Активного матча нет.").setEphemeral(true).queue();
            return;
        }
        if (match.hasTeam(team)) {
            event.reply("⚠️ Состав команды уже выбран.").setEphemeral(true).queue();
            return;
        }

        event
            .replyModal(DraftModal.forTeam(match, team, event.getGuild()))
            .queue();
    }
/**
     * Обрабатывает выбор состава команды A в модалке драфта.
     *
     * <p>Команда B не выбирается: она добирается автоматически из игроков,
     * не попавших в A. Как только состав A зафиксирован, матч стартует —
     * подмена ников и разводка по голосовым.
     */
    private void handleDraftSubmit(ModalInteractionEvent event, char team) {
        String categoryId = DraftModal.categoryIdOf(event.getModalId());
        CloseRegistration close = CloseRegistrations.get(categoryId);
        if (close == null) {
            event.reply("❌ Этот клоз больше не активен.").setEphemeral(true).queue();
            return;
        }
        if (!requireModerator(event, close)) {
            return;
        }

        CloseMatch match = close.getCurrentMatch();
        if (match == null || !match.isCollecting()) {
            event.reply("❌ Активного матча нет.").setEphemeral(true).queue();
            return;
        }

        // из 5 селектов модалки собираем состав
        List<Long> members = new ArrayList<>();
        for (int slot = 0; slot < CloseMatch.TEAM_SIZE; slot++) {
            List<String> chosen = DraftModal.chosenPlayerId(event, slot);
            if (chosen.isEmpty()) {
                continue;
            }
            try {
                members.add(Long.parseLong(chosen.get(0)));
            } catch (NumberFormatException e) {
                event
                    .reply("❌ Не удалось разобрать выбор в слоте " + (slot + 1) + ".")
                    .setEphemeral(true)
                    .queue();
                return;
            }
        }

        String teamName = team == 'a' ? "A" : "B";
        if (members.size() != CloseMatch.TEAM_SIZE) {
            event
                .reply(
                    "❌ Команда " + teamName + ": выбрано " + members.size() +
                    " из " + CloseMatch.TEAM_SIZE + ". Нужно заполнить все слоты."
                )
                .setEphemeral(true)
                .queue();
            return;
        }
        if (members.stream().distinct().count() != CloseMatch.TEAM_SIZE) {
            event.reply("❌ В команде нельзя выбрать одного игрока дважды.")
                .setEphemeral(true)
                .queue();
            return;
        }
        if (!match.assignTeam(team, members)) {
            event.reply("❌ В составе есть игрок, который не записан на матч.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // команду B клозер не выбирает — она собирается из остатка
        List<Long> restTeam = match.assignRestTo('b');
        if (restTeam.size() != CloseMatch.TEAM_SIZE) {
            log.warn(
                "Матч #{}: в команду B попало {} игроков вместо {}",
                match.getNumber(),
                restTeam.size(),
                CloseMatch.TEAM_SIZE
            );
        }

        // отвечаем через hook: подтверждаем взаимодействие и сразу пишем в него
        deferThenReply(
            event,
            "🎮 Состав выбран, начинаем игру...\nКоманда B: " +
            restTeam.size() + " игрок(ов) из остатка."
        );
        beginGame(event, close, match);
    }

    /**
 * Подменяет ники и разводит игроков по голосовым.
 *
 * <p>Вызывается уже после подтверждения взаимодействия, поэтому ответ
 * уходит в hook. Если по какой-то причине hook недоступен, {@link
 * #replyTo} корректно откатится на обычный {@code reply()}.
 */
private void beginGame(
        GenericInteractionCreateEvent event,
        CloseRegistration close,
        CloseMatch match
    ) {
        VoiceChannel[] voices = findVoiceChannels(event, close);
        if (voices == null) {
            replyTo(event, "❌ Не найдены голосовые каналы команд.");
            return;
        }
        MatchGameService.Report report = new MatchGameService(
            matches,
            players,
            event.getGuild()
        ).startGame(match, voices[0], voices[1]);
        // игра идёт: матч уже не принимает запись — гасим кнопки управления
        CloseRegistrations.refreshMatchControl(event.getJDA(), close);
        replyTo(event, report.message());
    }

    /**
     * Возвращает ники участников и переводит их в голосовой «ожидание».
     */
    private void endGame(
        GenericInteractionCreateEvent event,
        CloseRegistration close,
        CloseMatch match
    ) {
        new MatchGameService(matches, players, event.getGuild())
            .endGame(match, findWaitingVoice(event, close));
    }

    /**
     * Перерисовывает кнопки управления матчем в «управление».
     *
     * <p>Подтверждать взаимодействие здесь не нужно: правка идёт по id
     * сообщения, а не по нажатию.
     */
    private void refreshMatchControl(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        CloseRegistrations.refreshMatchControl(event.getJDA(), close);
    }

    /**
     * Отправляет сообщение в hook, предварительно подтвердив взаимодействие.
     *
     * <p>Нужен там, где ответ может не поместиться в 3 секунды (БД, Discord).
     * {@code deferReply(...)} — асинхронная операция, поэтому ждём её через
     * {@code queue(...)} с колбэком: если бы мы отправили в hook раньше,
     * JDA бросил бы исключение, так как hook ещё не создан.
     */
    private static void deferThenReply(
        GenericInteractionCreateEvent event,
        String message
    ) {
        if (event instanceof ButtonInteractionEvent button) {
            button.deferReply(true).queue(
                hook -> hook.sendMessage(message).queue(),
                error -> log.warn("Не удалось отправить ответ", error)
            );
        } else if (event instanceof ModalInteractionEvent modal) {
            modal.deferReply(true).queue(
                hook -> hook.sendMessage(message).queue(),
                error -> log.warn("Не удалось отправить ответ", error)
            );
        }
    }

    /**
     * Ответ пользователю.
     *
     * <p>Важно: {@code getHook()} доступен только после {@code deferReply()},
     * иначе JDA бросает исключение — а оно прервало бы обработку события
     * прямо на полпути (например, до удаления категории). Поэтому, если
     * ответа ещё не было, отвечаем обычным {@code reply()} с флагом
     * {@code ephemeral}; если уже был — пишем в hook.
     */
    private static void replyTo(GenericInteractionCreateEvent event, String message) {
        boolean acknowledged = event.isAcknowledged();
        if (event instanceof ButtonInteractionEvent button) {
            if (acknowledged) {
                button.getHook().sendMessage(message).queue();
            } else {
                button.reply(message).setEphemeral(true).queue();
            }
        } else if (event instanceof ModalInteractionEvent modal) {
            if (acknowledged) {
                modal.getHook().sendMessage(message).queue();
            } else {
                modal.reply(message).setEphemeral(true).queue();
            }
        }
    }

    /** Голосовые каналы команд по именам в категории клоза. */
    private static VoiceChannel[] findVoiceChannels(
        GenericInteractionCreateEvent event,
        CloseRegistration close
    ) {
        Guild guild = event.getGuild();
        Category category =
            guild == null ? null : guild.getCategoryById(close.getCategoryId());
        if (category == null) {
            return null;
        }
        VoiceChannel voiceA = findVoice(category, DotaCloseService.VOICE_A);
        VoiceChannel voiceB = findVoice(category, DotaCloseService.VOICE_B);
        return voiceA == null || voiceB == null
            ? null
            : new VoiceChannel[] { voiceA, voiceB };
    }

    private static VoiceChannel findVoice(Category category, String name) {
        return category
            .getVoiceChannels()
            .stream()
            .filter(voice -> voice.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Переводит игрока в голосовой канал ожидания.
     *
     * <p>Ошибки не показываем игроку: запись уже прошла, а перемещение —
     * вспомогательное действие. Если канала нет или не хватает прав, пишем
     * только в лог.
     */
    private void moveToWaiting(
        GenericInteractionCreateEvent event,
        CloseRegistration close,
        long discordId
    ) {
        // перевод в голосовой — вспомогательное действие: его сбой (нет прав,
        // участник вышел с сервера, канал недоступен) не должен прерывать
        // обработку нажатия уже после того, как запись засчитана
        try {
            VoiceChannel waiting = findWaitingVoice(event, close);
            if (waiting == null) {
                log.warn(
                    "Голосовой канал ожидания клоза {} не найден",
                    close.getCategoryId()
                );
                return;
            }
            new MatchGameService(matches, players, event.getGuild())
                .moveToWaiting(discordId, waiting);
        } catch (RuntimeException e) {
            log.warn("Не удалось перевести игрока {} в «ожидание»", discordId, e);
        }
    }

    /** Голосовой канал ожидания по имени в категории клоза. */
    private static VoiceChannel findWaitingVoice(
        GenericInteractionCreateEvent event,
        CloseRegistration close
    ) {
        Guild guild = event.getGuild();
        Category category =
            guild == null ? null : guild.getCategoryById(close.getCategoryId());
        return category == null
            ? null
            : findVoice(category, DotaCloseService.VOICE_WAITING);
    }

    /** Отвечает отказом, если активного собирающегося матча нет. */
    private CloseMatch requireCollectingMatch(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        CloseMatch match = close.getCurrentMatch();
        if (match == null || !match.isCollecting()) {
            event.reply("❌ Сейчас нет матча, который набирает игроков.")
                .setEphemeral(true)
                .queue();
            return null;
        }
        return match;
    }

    /** Управлять клозом и матчами может клозер или модератор. */
    private boolean requireModerator(
        GenericInteractionCreateEvent event,
        CloseRegistration close
    ) {
        Member member = event.getMember();
        if (member == null || !canManage(member, close)) {
            replyTo(event, "❌ Управлять матчами может только Closemod или сам клозер.");
            return false;
        }
        return true;
    }

    private static boolean canManage(Member member, CloseRegistration close) {
        if (member.getIdLong() == close.getHostId()) {
            return true;
        }
        return member
            .getRoles()
            .stream()
            .anyMatch(role -> role.getName().equalsIgnoreCase("Closemod"));
    }

    /** Создаёт матч в БД и возвращает его состояние в памяти. */
    private CloseMatch createMatch(CloseRegistration close) throws SQLException {
        CloseRegistration.MatchSettings settings = close.settings();
        int number = close.nextMatchNumber();
        long matchId = matches.create(
            close.getCloseId(),
            number,
            settings.gamemode(),
            settings.immortalDraft(),
            settings.toxic()
        );
        return new CloseMatch(
            matchId,
            close.getCloseId(),
            close.getCategoryId(),
            number,
            close.getHostId(),
            settings.immortalDraft(),
            settings.gamemode(),
            settings.toxic()
        );
    }
/** Публикует карточку поиска игроков в канале «запись». */
    private void announceMatch(
        ButtonInteractionEvent event,
        CloseRegistration close,
        CloseMatch match
    ) {
        TextChannel signupChannel = findSignupChannel(event, close);
        if (signupChannel == null) {
            replyTo(event, "❌ Не найден канал «запись».");
            return;
        }

        match.markAnnounced();
        // матч объявлен — «Проверка готовности» и «Завершить матч» оживают
        refreshMatchControl(event, close);
        signupChannel
            .sendMessage(match.render())
            .setComponents(CloseRegistrations.buttons(match))
            .queue(
                message ->
                    match.markSignupMessage(
                        signupChannel.getIdLong(),
                        message.getIdLong()
                    ),
                error ->
                    log.error(
                        "Не удалось отправить карточку матча #{}",
                        match.getNumber(),
                        error
                    )
            );
        // итоговое сообщение уже отправлено вызывающим через deferThenReply
    }

    /** Канал «запись» внутри категории клоза. */
    private static TextChannel findSignupChannel(
        ButtonInteractionEvent event,
        CloseRegistration close
    ) {
        Guild guild = event.getGuild();
        Category category =
            guild == null ? null : guild.getCategoryById(close.getCategoryId());
        if (category == null) {
            return null;
        }
        return category.getTextChannels()
            .stream()
            .filter(text -> text.getName().equals("запись"))
            .findFirst()
            .orElse(null);
    }

    /**
     * Перерисовывает карточку матча после изменения состава.
     *
     * <p>Правим сообщение по сохранённому id, а не по нажатой кнопке: игрок
     * мог нажать «Записаться» на старой карточке (например, её уже перерисовала
     * проверка готовности), и тогда правка ушла бы не туда и запись осталась
     * бы незамеченной. Если id ещё нет — fallback на сообщение нажатия.
     */
    private void refreshSignupCard(
        ButtonInteractionEvent event,
        CloseMatch match
    ) {
        refreshSignupCard(
            event,
            match,
            "✅ Записан на матч #" + match.getNumber() + "."
        );
    }

    /**
     * Перерисовывает карточку состава.
     *
     * <p>Важно: правка сообщения по id ({@code editMessageById}) — это обычный
     * REST-запрос, он <em>не</em> подтверждает взаимодействие. Без отдельного
     * подтверждения Discord через три секунды покажет игроку «приложение не
     * отвечает», хотя запись уже есть. Поэтому в этом пути подтверждаем
     * взаимодействие явно и уже затем правим карточку.
     *
     * @param confirmation что показать игроку в эфемерном ответе
     */
    private void refreshSignupCard(
        ButtonInteractionEvent event,
        CloseMatch match,
        String confirmation
    ) {
        String text = match.render();
        ActionRow row = CloseRegistrations.buttons(match);

        Long messageId = match.getSignupMessageId();
        if (messageId != null && messageId == event.getMessageIdLong()) {
            // карточка — то самое сообщение, на котором нажали: одна правка
            // заодно подтверждает взаимодействие
            editCard(event, text, row, match);
            return;
        }

        // подтверждаем до любой другой работы с Discord — иначе не уложимся
        // в три секунды
        acknowledge(event, confirmation);

        Long channelId = match.getSignupChannelId();
        if (messageId == null || channelId == null) {
            return; // карточка ещё не опубликована — править нечего
        }

        Guild guild = event.getGuild();
        GuildChannel channel = guild == null
            ? null
            : guild.getChannelById(GuildChannel.class, channelId);
        if (!(channel instanceof TextChannel textChannel)) {
            log.warn(
                "Карточка матча #{} не найдена (канал {}), пропускаю правку",
                match.getNumber(),
                channelId
            );
            return;
        }

        textChannel
            .editMessageById(messageId, text)
            .setComponents(row)
            .queue(
                null,
                error ->
                    log.error(
                        "Не удалось обновить карточку матча #{} по id {}",
                        match.getNumber(),
                        messageId,
                        error
                    )
            );
    }

    /**
     * Подтверждает взаимодействие без текста на месте нажатия: карточка
     * обновится отдельно, а игрок получит короткий эфемерный ответ.
     */
    private static void acknowledge(
        GenericInteractionCreateEvent event,
        String message
    ) {
        if (event.isAcknowledged() || !(event instanceof ButtonInteractionEvent button)) {
            return;
        }
        button.deferEdit().queue(
            hook -> hook.sendMessage(message).queue(),
            error ->
                log.warn("Не удалось подтвердить взаимодействие", error)
        );
    }

    /** Правка карточки по сообщению, на котором пришло нажатие. */
    private void editCard(
        ButtonInteractionEvent event,
        String text,
        ActionRow row,
        CloseMatch match
    ) {
        event
            .editMessage(text)
            .setComponents(row)
            .queue(
                null,
                error ->
                    log.error(
                        "Не удалось обновить карточку матча #{}",
                        match.getNumber(),
                        error
                    )
            );
    }

    /**
     * Состав в БД: пишем на каждое изменение, чтобы пережить рестарт бота.
     * Ошибка БД не отменяет запись в Discord — состав всё равно покажем.
     */
    private void persistParticipant(
        ButtonInteractionEvent event,
        CloseMatch match,
        long discordId,
        boolean joined
    ) {
        try {
            Member member = event.getMember();
            long playerId = players.ensureId(
                discordId,
                member == null ? null : member.getEffectiveName()
            );
            if (joined) {
                matches.addParticipant(match.getMatchId(), playerId, match.teamOf(discordId));
            } else {
                matches.removeParticipant(match.getMatchId(), playerId);
            }
        } catch (SQLException | RuntimeException e) {
            log.error("Не удалось сохранить состав матча #{}", match.getNumber(), e);
        }
    }

    /** Закрывает карточку матча: кнопки убираются, остаётся итоговый состав. */
    private void lockSignupCard(ButtonInteractionEvent event, CloseMatch match) {
        TextChannel channel = channelOf(event, match);
        if (channel == null) {
            return;
        }
        channel
            .editMessageById(match.getSignupMessageId(), match.renderResult())
            .setComponents()
            .queue(
                null,
                error ->
                    log.warn("Не удалось закрыть карточку матча #{}", match.getNumber())
            );
    }

    private TextChannel channelOf(ButtonInteractionEvent event, CloseMatch match) {
        Long channelId = match.getSignupChannelId();
        if (channelId == null || match.getSignupMessageId() == null) {
            return null; // карточка ещё не публиковалась
        }
        return event.getJDA().getChannelById(TextChannel.class, channelId);
    }

    /** Все кнопки клоза. */
    private static final List<String> ACTIONS = List.of(
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
    );

    /** null — если customId не наш. */
    static String actionOf(String componentId) {
        for (String action : ACTIONS) {
            if (componentId.startsWith(action + ':')) {
                return action;
            }
        }
        return null;
    }

    static String closeIdOf(String componentId) {
        return componentId.substring(componentId.indexOf(':') + 1);
    }

    /** То же, что {@link #closeIdOf}, но с именем, отражающим реальность. */
    static String categoryIdOf(String componentId) {
        return closeIdOf(componentId);
    }
}
