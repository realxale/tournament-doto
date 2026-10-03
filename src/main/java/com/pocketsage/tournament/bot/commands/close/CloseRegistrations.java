package com.pocketsage.tournament.bot.commands.close;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Реестр активных клозов и все кнопки клоза.
 *
 * <p>Класс отвечает только за две вещи: помнить, какие клозы сейчас работают,
 * и уметь нарисовать нужный ряд кнопок. Логику нажатий разбирает
 * {@link CloseRegistrationHandler}, состояние матча хранит {@link CloseMatch}.
 *
 * <p><b>Ключ реестра</b> — id категории Discord клоза. Он же уходит в custom_id
 * кнопок: он переживает перезапуск бота (в отличие от счётчиков матчей) и всегда
 * короче лимита Discord в 100 символов.
 *
 * <p><b>Состояние клоза</b> живёт в памяти процесса: после перезапуска бота
 * записи обнуляются. Состав и времена матчей в БД остаются.
 *
 * <p><b>Куда что попадает:</b>
 * <ul>
 *   <li>канал «запись» — {@link #buttons}: запись игроков;</li>
 *   <li>канал «управление» — {@link #matchControlButtons} (матч) и
 *       {@link #controlButtons} (клоз целиком);</li>
 *   <li>сообщение проверки готовности — {@link #readyButtons};</li>
 *   <li>эфемерный ответ при драфте — {@link #draftButtons}.</li>
 * </ul>
 */
public final class CloseRegistrations {

    // ===== Запись игроков в матч (канал «запись») =====
    /**
     * Запись без выбора команды — только immortal draft.
     *
     * <p>В обычном матче игрок сам выбирает команду, поэтому там три отдельных
     * действия: {@link #ACTION_SIGNUP_A}, {@link #ACTION_SIGNUP_B} и
     * {@link #ACTION_SIGNOFF}.
     */
    public static final String ACTION_SIGNUP = "close_signup";

    /** Запись в команду A (только обычный матч). */
    public static final String ACTION_SIGNUP_A = "close_signup_a";

    /** Запись в команду B (только обычный матч). */
    public static final String ACTION_SIGNUP_B = "close_signup_b";

    /** Выход из матча, независимо от выбранной команды. */
    public static final String ACTION_SIGNOFF = "close_signoff";

    // ===== Управление матчем (канал «управление») =====
    /**
     * Создание матча и публикация карточки поиска игроков в «запись».
     *
     * <p>Совпадает по смыслу с кнопкой «Создать матч»: действие создаёт матч,
     * а не начинает игру — в этот момент игроки ещё только записываются.
     */
    public static final String ACTION_MATCH_START = "match_start";

    /** Отмена начатого матча: он помечается CANCELLED, карточка удаляется. */
    public static final String ACTION_MATCH_DELETE = "match_delete";

    /** Завершение сыгранного матча: состав фиксируется, карточка запирается. */
    public static final String ACTION_MATCH_FINISH = "match_finish";

    // ===== Проверка готовности (внутри матча) =====

    /** Отклик игрока «Я готов» под сообщением проверки. */
    public static final String ACTION_READY = "close_ready";

    /**
     * Запуск окна подтверждений по текущему составу матча.
     *
     * <p>Кнопка живёт в управлении матчем ({@link #matchControlButtons}), а не
     * в карточке записи: запускает её клозер, игроки только отвечают на
     * сообщение проверки, которое уходит в «запись».
     */
    public static final String ACTION_READY_START = "match_ready_start";

    // ===== Старт игры =====
    /**
     * Начать игру: подменить ники на Steam-ники и развести игроков по голосовым.
     *
     * <p>Активна только когда матч объявлен и собраны все участники — иначе
     * разводить по голосовым некого.
     */
    public static final String ACTION_GAME_START = "match_game_start";

    // ===== Immortal draft: состав команды A =====

    /**
     * Выбор состава команды A.
     *
     * <p>Команда B не выбирается: клозер задаёт только A, а B автоматически
     * добирается из игроков, не попавших в A.
     */
    public static final String ACTION_DRAFT_A = "match_draft_a";

    // ===== Управление клозом (канал «управление») =====

    /** Отмена всего клоза вместе с его каналами. */
    public static final String ACTION_CLOSE_DELETE = "close_delete";

    private static final Logger log = LoggerFactory.getLogger(CloseRegistrations.class);

    private static final Map<String, CloseRegistration> ACTIVE = new ConcurrentHashMap<>();

    private CloseRegistrations() {}

    // ===== Реестр активных клозов =====

    /**
     * Регистрирует клоз: с этого момента его кнопки начинают обрабатываться.
     *
     * <p>Ключ — id категории Discord: он же зашит в custom_id кнопок, поэтому
     * по нему обработчик находит нужный клоз без обращения к БД.
     */
    public static void add(CloseRegistration registration) {
        ACTIVE.put(registration.getCategoryId(), registration);
    }

    /**
     * Клоз по id категории из custom_id нажатой кнопки.
     *
     * @return null — клоз уже не активен (удалён или бот перезапущен)
     */
    public static CloseRegistration get(String categoryId) {
        return ACTIVE.get(categoryId);
    }

    /** Клоз окончательно уходит — забываем его, кнопки перестают работать. */
    public static CloseRegistration remove(String categoryId) {
        return ACTIVE.remove(categoryId);
    }

    // ===== Кнопки канала «запись»: действия игрока =====

    /**
     * Ряд кнопок под карточкой матча — действия игрока.
     *
     * <p>Набор зависит от режима матча:
     * <ul>
     *   <li>immortal draft — команды формирует клозер, игроку нужна одна
     *       кнопка «Записаться»;</li>
     *   <li>обычный матч — игрок сам выбирает команду, поэтому кнопки A и B
     *       гаснут по мере заполнения соответствующей команды.</li>
     * </ul>
     *
     * <p>Общее для обоих режимов: пока матч собирается, писать можно; когда он
     * закрыт или в обычном матче заняты все 10 мест — запись невозможна.
     */
    public static ActionRow buttons(CloseMatch match) {
        // матч открыт только пока он в состоянии COLLECTING
        boolean open = match.isCollecting();
        // id категории нужен для custom_id: по нему обработчик найдёт клоз
        String categoryId = match.getCloseCategoryId();

        if (match.isImmortalDraft()) {
            return ActionRow.of(
                // свободных мест в драфте не считаем по командам — считаем все
                Button.success(buttonId(ACTION_SIGNUP, categoryId), "Записаться")
                    .withDisabled(!open || match.isFull()),
                Button.danger(buttonId(ACTION_SIGNOFF, categoryId), "Отписаться")
            );
        }

        return ActionRow.of(
            // в обычном матче места считаются отдельно по каждой команде
            Button.success(buttonId(ACTION_SIGNUP_A, categoryId), "В команду A")
                .withDisabled(!open || match.isTeamFull('a')),
            Button.success(buttonId(ACTION_SIGNUP_B, categoryId), "В команду B")
                .withDisabled(!open || match.isTeamFull('b')),
            Button.danger(buttonId(ACTION_SIGNOFF, categoryId), "Отписаться")
        );
    }

    // ===== Кнопки канала «управление»: действия клозера =====

    /**
     * Кнопки управления текущим матчем.
     *
     * <p>Каждая кнопка активна только в своём состоянии матча — так проще
     * не дать клозеру сделать нелогичный переход:
     * <ul>
     *   <li>«Создать матч» — пока матч ещё не объявлен;</li>
     *   <li>«Проверка готовности» — матч объявлен и в нём есть хоть один
     *       игрок, иначе проверять некого;</li>
     *   <li>«Начать игру» — матч объявлен <em>и</em> собраны все участники,
     *       иначе разводить по голосовым некого;</li>
     *   <li>«Удалить матч» — пока есть что удалять;</li>
     *   <li>«Завершить матч» — только у объявленного матча.</li>
     * </ul>
     *
     * <p>Ряд содержит пять кнопок — это ровно лимит Discord на один ActionRow,
     * поэтому новых действий матча сюда больше не помещается: либо
     * {@link #withDisabled} для существующей, либо второй ряд.
     */
    public static ActionRow matchControlButtons(CloseRegistration close) {
        CloseMatch match = close.getCurrentMatch();
        String categoryId = close.getCategoryId();

        // матч существует, принимает запись и уже опубликован в «записи»
        boolean announced =
            match != null && match.isCollecting() && match.isAnnounced();
        // проверять готовность есть смысл, только когда есть кому отвечать
        boolean canCheckReady = announced && !match.participants().isEmpty();
        // играть можно, только когда собраны все десять игроков
        boolean canStartGame = announced && match.size() >= CloseMatch.SLOTS;

        return ActionRow.of(
            Button.primary(buttonId(ACTION_MATCH_START, categoryId), "Создать матч")
                .withDisabled(announced),
            Button.secondary(
                    buttonId(ACTION_READY_START, categoryId),
                    "Проверка готовности"
                )
                .withDisabled(!canCheckReady),
            Button.success(buttonId(ACTION_GAME_START, categoryId), "Начать игру")
                .withDisabled(!canStartGame),
            Button.danger(buttonId(ACTION_MATCH_DELETE, categoryId), "Удалить матч")
                .withDisabled(match == null),
            Button.success(buttonId(ACTION_MATCH_FINISH, categoryId), "Завершить матч")
                .withDisabled(!announced)
        );
    }

    /**
     * Ряд кнопок сообщения о самом клозе: отмена клоза.
     *
     * <p>Намеренно без состояний: клоз можно отменить в любой момент, а матчи
     * им управляются на соседнем сообщении ({@link #matchControlButtons}).
     */
    public static ActionRow controlButtons(String categoryId) {
        return ActionRow.of(
            Button.danger(buttonId(ACTION_CLOSE_DELETE, categoryId), "Удалить клоз")
        );
    }

    // ===== Кнопки вне карточки: драфт и проверка готовности =====

    /**
     * Кнопка выбора состава команды A при immortal draft.
     *
     * <p>Состав команды B не выбирается: он добирается из оставшихся игроков
     * автоматически. После выбора A кнопка гаснет — состав уже зафиксирован,
     * менять его второй раз нельзя.
     */
    public static ActionRow draftButtons(CloseMatch match) {
        return ActionRow.of(
            Button.primary(
                    buttonId(ACTION_DRAFT_A, match.getCloseCategoryId()),
                    "Состав команды A"
                )
                .withDisabled(match.hasTeam('a'))
        );
    }

    /**
     * Кнопка «Я готов» под сообщением проверки готовности.
     *
     * <p>Сама проверка ей не управляется: окно открывает клозер кнопкой
     * {@link #ACTION_READY_START}, а эта кнопка только собирает отклики.
     */
    public static ActionRow readyButtons(String categoryId) {
        return ActionRow.of(
            Button.success(buttonId(ACTION_READY, categoryId), "Я готов")
        );
    }

    // ===== Обновление карточки без нажатия =====

    /**
     * Перерисовывает карточку матча, когда нажатия кнопки не было.
     *
     * <p>Так бывает, когда состав меняет таймер: проверка готовности по истечении
     * окна выписывает не подтвердивших и зовёт этот метод.
     *
     * <p>Важно: правка идёт по id сообщения, а не по нажатию, поэтому здесь
     * взаимодействие подтверждать не нужно — его и не было.
     */
    public static void refreshSignupCard(JDA jda, CloseMatch match) {
        // id карточки известны только после её успешной отправки
        Long channelId = match.getSignupChannelId();
        Long messageId = match.getSignupMessageId();
        if (channelId == null || messageId == null) {
            return;
        }

        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (!(channel instanceof TextChannel text)) {
            return; // клоз удалён — канала уже нет
        }

        text
            .editMessageById(messageId, match.render())
            .setComponents(buttons(match))
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
     * Текст сообщения «Матчи клоза» из «управления».
     *
     * <p>Живёт здесь, а не в {@code DotaCloseService}, потому что нужен в двух
     * местах: при первой отправке и при перерисовке кнопок — Discord требует
     * текст целиком, так что обе точки обязаны давать одинаковый результат.
     *
     * @param logChannel канал «лог» клоза, на него ведёт подсказка
     */
    public static String matchControlText(CloseRegistration close, TextChannel logChannel) {
        return """
            ⚔️ **Матчи клоза #%d**
            Слоты в матче: %d

            «Создать матч» — создаёт матч и публикует поиск игроков в %s.
            «Проверка готовности» — окно подтверждений в голосовом «ожидание».
            «Начать игру» — раскладывает игроков по голосовым каналам команд.
            «Завершить матч» — фиксирует состав и время конца.
            «Удалить матч» — убирает матч, не доиграв.
            """.formatted(close.getNumber(), CloseMatch.SLOTS, logChannel.getAsMention());
    }

    /**
     * Перерисовывает кнопки управления матчем в «управление».
     *
     * <p>Сообщение с этими кнопками отправляется один раз при создании клоза,
     * поэтому без явного обновления они навсегда остались бы в состоянии на
     * тот момент: кнопки активны/гаснут по мере объявления матча, записи
     * игроков и его завершения.
     *
     * <p>Здесь правка идёт по id сообщения, а не по нажатию, поэтому
     * взаимодействие подтверждать не нужно — его и не было.
     *
     * <p>Если id неизвестен (клоз создан прошлой версией бота и пережил
     * рестарт — тогда сообщение осталось без кнопок), сообщение публикуется
     * заново: перерисовать то, чьего id у нас нет, невозможно.
     */
    public static void refreshMatchControl(JDA jda, CloseRegistration close) {
        Long channelId = close.getControlChannelId();
        Long messageId = close.getControlMessageId();
        if (channelId == null || messageId == null) {
            republishMatchControl(jda, close);
            return;
        }

        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (!(channel instanceof TextChannel text)) {
            return; // клоз удалён — канала уже нет
        }

        // Discord при правке требует текст целиком, поэтому отдаём сохранённый:
        // пересборка заново могла бы разойтись с тем, что видит клозер
        text
            .editMessageById(messageId, close.getControlText())
            .setComponents(matchControlButtons(close))
            .queue(
                null,
                error ->
                    log.error(
                        "Не удалось обновить кнопки матча клоза {}",
                        close.getNumber(),
                        error
                    )
            );
    }

    /**
     * Публикует сообщение «Матчи клоза» заново и запоминает его id.
     *
     * <p>Запасной путь для клозов, созданных до появления этого сообщения:
     * у них в памяти нет ни канала, ни сообщения, поэтому единственный способ
     * показать клозеру актуальные кнопки — отправить их заново.
     */
    private static void republishMatchControl(JDA jda, CloseRegistration close) {
        Category category = jda.getCategoryById(close.getCategoryId());
        if (category == null) {
            return; // категория удалена — клоз уже не существует
        }
        TextChannel control = category
            .getTextChannels()
            .stream()
            .filter(text -> text.getName().equals("управление"))
            .findFirst()
            .orElse(null);
        TextChannel logChannel = category
            .getTextChannels()
            .stream()
            .filter(text -> text.getName().equals("лог"))
            .findFirst()
            .orElse(null);
        if (control == null || logChannel == null) {
            log.warn(
                "Не удалось обновить кнопки клоза {}: нет каналов «управление»/«лог»",
                close.getNumber()
            );
            return;
        }

        String text = matchControlText(close, logChannel);
        control
            .sendMessage(text)
            .setComponents(matchControlButtons(close))
            .queue(
                message ->
                    close.markControlMessage(
                        control.getIdLong(),
                        message.getIdLong(),
                        text
                    ),
                error ->
                    log.error(
                        "Не удалось опубликовать кнопки матча клоза {}",
                        close.getNumber(),
                        error
                    )
            );
    }

    // ===== custom_id кнопок =====

    /**
     * Собирает custom_id кнопки: {@code действие:id_категории}.
     *
     * <p>Разделитель «:» — потому что обработчик разбирает строку по нему и
     * слева получает действие, справа — категорию клоза.
     */
    public static String buttonId(String action, String categoryId) {
        return action + ':' + categoryId;
    }
}
