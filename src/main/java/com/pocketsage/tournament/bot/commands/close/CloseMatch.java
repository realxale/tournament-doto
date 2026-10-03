package com.pocketsage.tournament.bot.commands.close;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Состояние одного матча клоза: параметры, состав и кто подтвердил готовность.
 *
 * <p>Клоз — серия матчей, поэтому состав живёт здесь, а не в клозе: каждый матч
 * играется отдельным составом и записывается в БД отдельной строкой.
 *
 * <p>Индекс в списке участников = номер слота в сообщении записи.
 * Без immortal draft слоты 1..5 — команда A, 6..10 — команда B
 * (заполняются по порядку записи).
 *
 * <p>Экземпляр меняется из потоков JDA (нажатия кнопок), поэтому методы
 * синхронизированы.
 */
public class CloseMatch {

    /** Всего мест в матче. */
    public static final int SLOTS = 10;

    /** Размер одной команды (для матчей без immortal draft). */
    public static final int TEAM_SIZE = 5;

    private final long matchId; // id строки в таблице match
    private final long closeId; // id клоза в таблице close
    private final String closeCategoryId; // категория Discord — попадает в customId кнопок
    private final int number; // номер матча внутри клоза: 1, 2, ...
    private final long hostId; // discord id клозера
    private final boolean immortalDraft;
    private final String gamemode; // "cm" | "ap"
    private final boolean toxic;
    private final List<Long> participants = new ArrayList<>();
    private final Set<Long> ready = new LinkedHashSet<>();

    /** Жизненный цикл матча: COLLECTING → PLAYING → FINISHED / CANCELLED. */
    public enum State {
        COLLECTING,
        PLAYING,
        FINISHED,
        CANCELLED
    }

    private volatile State state = State.COLLECTING;
    private volatile boolean announced; // карточка поиска игроков опубликована в «запись»

    /**
     * Явное распределение по командам (immortal draft).
     *
     * <p>Без драфта команды выводится из порядка записи, поэтому карта пустая.
     * С драфтом клозер сам выбирает состав каждой команды.
     */
    private final Map<Long, Character> assignedTeams = new HashMap<>();

    // id сообщений/каналов нужны, чтобы обновлять карточку не только из нажатий кнопок
    private volatile Long signupChannelId; // «запись»: канал карточки поиска игроков
    private volatile Long signupMessageId; // карточка поиска игроков

    public CloseMatch(
        long matchId,
        long closeId,
        String closeCategoryId,
        int number,
        long hostId,
        boolean immortalDraft,
        String gamemode,
        boolean toxic
    ) {
        this.matchId = matchId;
        this.closeId = closeId;
        this.closeCategoryId = closeCategoryId;
        this.number = number;
        this.hostId = hostId;
        this.immortalDraft = immortalDraft;
        this.gamemode = gamemode;
        this.toxic = toxic;
    }

    public long getMatchId() {
        return matchId;
    }

    public long getCloseId() {
        return closeId;
    }

    /** Категория Discord клоза — ключ реестра и часть customId кнопок. */
    public String getCloseCategoryId() {
        return closeCategoryId;
    }

    public int getNumber() {
        return number;
    }

    public long getHostId() {
        return hostId;
    }

    /** true — матч immortal draft: команды формирует клозер, а не игроки. */
    public boolean isImmortalDraft() {
        return immortalDraft;
    }

    public State getState() {
        return state;
    }

    /** true — матч ещё собирается и в него можно записаться. */
    public boolean isCollecting() {
        return state == State.COLLECTING;
    }

    /** true — игра идёт: ники подменены, игроки разведены по голосовым. */
    public boolean isPlaying() {
        return state == State.PLAYING;
    }

    /** Матч переходит в игру. false — если он уже не COLLECTING. */
    public synchronized boolean markPlaying() {
        if (state != State.COLLECTING) {
            return false;
        }
        state = State.PLAYING;
        return true;
    }

    /**
     * Команда игрока: явно выбранная при драфте, иначе — по порядку записи.
     *
     * <p>Порядок записи: слоты 1..5 — команда 'a', 6..10 — 'b'.
     */
    public synchronized char teamOf(long discordId) {
        Character assigned = assignedTeams.get(discordId);
        if (assigned != null) {
            return assigned;
        }
        int index = participants.indexOf((Long) discordId);
        return index < 0 ? 'a' : (index < TEAM_SIZE ? 'a' : 'b');
    }

    /**
     * Фиксирует состав команды при драфте.
     *
     * @param team  'a' или 'b'
     * @param members ровно {@link #TEAM_SIZE} участников
     * @return false — если размер команды неверный или игрок не записан
     */
    public synchronized boolean assignTeam(
        char team,
        List<Long> members
    ) {
        if (members == null || members.size() != TEAM_SIZE) {
            return false;
        }
        for (long discordId : members) {
            if (!participants.contains((Long) discordId)) {
                return false;
            }
        }
        for (long discordId : members) {
            assignedTeams.put(discordId, team);
        }
        return true;
    }

    /**
     * Добивает оставшихся игроков в указанную команду.
     *
     * <p>Нужен для immortal draft: клозер выбирает только состав команды A,
     * а команда B собирается автоматически из тех, кто не попал в неё.
     *
     * @return распределённые игроки; пустой список, если добрать некого
     */
    public synchronized List<Long> assignRestTo(char team) {
        List<Long> rest = participants
            .stream()
            .filter(id -> !assignedTeams.containsKey(id))
            .toList();
        if (rest.isEmpty()) {
            return rest;
        }
        for (long discordId : rest) {
            assignedTeams.put(discordId, team);
        }
        return rest;
    }

    /** Уже выбрана ли команда при драфте. */
    public synchronized boolean hasTeam(char team) {
        for (Character value : assignedTeams.values()) {
            if (value == team) {
                return true;
            }
        }
        return false;
    }

    /** Состав команды по порядку записи (или по драфту). */
    public synchronized List<Long> teamMembers(char team) {
        return participants
            .stream()
            .filter(id -> teamOf(id) == team)
            .toList();
    }

    /** true — карточка поиска игроков уже опубликована в «запись». */
    public boolean isAnnounced() {
        return announced;
    }

    public void markAnnounced() {
        this.announced = true;
    }

    public synchronized void markFinished() {
        state = State.FINISHED;
    }

    public synchronized void markCancelled() {
        state = State.CANCELLED;
    }

    /** Записывает игрока. false — он уже записан, все места заняты или матч не собирается. */
    public synchronized boolean signUp(long discordId) {
        if (!isCollecting() || participants.contains(discordId) || isFull()) {
            return false;
        }
        participants.add(discordId);
        return true;
    }

    /**
     * Записывает игрока в конкретную команду.
     *
     * <p>При обычном матче игрок сам выбирает команду, поэтому проверяем
     * свободные места именно в ней: иначе можно было бы забить команду A
     * пятью игроками и обнаружить, что мест больше нет ни там, ни там.
     *
     * @return false — уже записан, матч не собирается или в команде нет мест
     */
    public synchronized boolean signUpToTeam(long discordId, char team) {
        if (!isCollecting() || participants.contains((Long) discordId)) {
            return false;
        }
        if (isTeamFull(team)) {
            return false;
        }
        participants.add(discordId);
        assignedTeams.put(discordId, team);
        return true;
    }

    /** true — в команде закончились места. */
    public synchronized boolean isTeamFull(char team) {
        return teamMembers(team).size() >= TEAM_SIZE;
    }

    /** Сколько игроков в команде. */
    public synchronized int teamSize(char team) {
        return teamMembers(team).size();
    }

    /** Отписывает игрока. false — его не было в записи. */
    public synchronized boolean signOff(long discordId) {
        assignedTeams.remove((Long) discordId);
        return participants.remove((Long) discordId);
    }

    public synchronized boolean isSignedUp(long discordId) {
        return participants.contains(discordId);
    }

    /** true — мест больше нет (кнопка «Записаться» блокируется). */
    public synchronized boolean isFull() {
        return participants.size() >= SLOTS;
    }

    public synchronized int size() {
        return participants.size();
    }

    /** Копия списка участников в порядке записи. */
    public synchronized List<Long> participants() {
        return List.copyOf(participants);
    }

    // ===== Запуск проверки готовности =====

    /** Новая проверка: прошлые подтверждения сбрасываются. */
    public synchronized void startReadinessCheck() {
        ready.clear();
    }

    /** Подтверждает готовность. false — игрок не записан или уже подтверждал. */
    public synchronized boolean confirmReady(long discordId) {
        if (!participants.contains(discordId)) {
            return false;
        }
        return ready.add(discordId);
    }

    public synchronized boolean isReady(long discordId) {
        return ready.contains(discordId);
    }

    /** Записанные, кто ещё не подтвердил готовность. */
    public synchronized List<Long> notReady() {
        return participants
            .stream()
            .filter(discordId -> !ready.contains(discordId))
            .toList();
    }

    /** Выписывает всех, кто не подтвердил готовность. Возвращает выписанных. */
    public synchronized List<Long> signOffNotReady() {
        List<Long> kicked = new ArrayList<>(notReady());
        participants.removeAll(kicked);
        ready.removeAll(kicked);
        return kicked;
    }

    /** Текст сообщения проверки готовности; кнопку «Я готов» прикрепляет вызывающий. */
    public synchronized String renderReadiness(long deadlineEpochSeconds) {
        StringBuilder text = new StringBuilder();
        text.append("⏱️ **Проверка готовности**\n")
            .append("Клозер: ")
            .append(mention(hostId))
            .append('\n')
            .append("Нажмите «Я готов» до <t:")
            .append(deadlineEpochSeconds)
            .append(":R> — кто не нажмёт, автоматически выпишется из клоза.\n")
            .append("Подтверждать нужно из голосового канала **ожидание**: кто оттуда вышел, ")
            .append("тоже считается не явившимся.\n");

        if (participants.isEmpty()) {
            return text.append("\n⚠️ Пока никто не записан.\n").toString();
        }

        List<Long> confirmed = participants
            .stream()
            .filter(ready::contains)
            .toList();
        List<Long> waiting = notReady();
        return text
            .append("\n✅ Подтвердили (")
            .append(confirmed.size())
            .append("): ")
            .append(mentions(confirmed))
            .append('\n')
            .append("⏳ Ждём (")
            .append(waiting.size())
            .append("): ")
            .append(mentions(waiting))
            .append('\n')
            .toString();
    }

    /** Итоговая карточка доигранного матча: состав зафиксирован, записи нет. */
    public synchronized String renderResult() {
        return (
            render() +
            "\n🏁 **Матч завершён** — состав зафиксирован."
        );
    }

    /** Итог проверки готовности. */
    public String renderReadinessResult(List<Long> kicked) {
        if (kicked.isEmpty()) {
            return "✅ **Проверка готовности завершена**\nВсе подтвердили — никто не выписан.";
        }
        return (
            "⏱️ **Проверка готовности завершена**\nВыписаны за неявку: " +
            mentions(kicked)
        );
    }

    // ===== Где лежит карточка матча =====

    public void markSignupMessage(long channelId, long messageId) {
        this.signupChannelId = channelId;
        this.signupMessageId = messageId;
    }

    public Long getSignupChannelId() {
        return signupChannelId;
    }

    public Long getSignupMessageId() {
        return signupMessageId;
    }

    private static String mentions(Collection<Long> discordIds) {
        if (discordIds.isEmpty()) {
            return "—";
        }
        return discordIds
            .stream()
            .map(CloseMatch::mention)
            .collect(Collectors.joining(", "));
    }

    /**
     * Кастомные эмодзи номеров слотов убраны намеренно.
     *
     * <p>Эмодзи вида {@code <:1slot:ID>} принадлежат чужому серверу: Discord
     * подставляет их в текст только если бот состоит с ним в одной гильдии, а
     * иначе игроки видят сырое «:1slot:». Поэтому номер слота — просто число.
     */

    /** Текст карточки матча в «запись» (кнопки прикрепляет вызывающий). */
    public synchronized String render() {
        StringBuilder text = new StringBuilder();
        text.append("## Запись\n")
            .append("**Игра:** Dota 2\n")
            .append("**Режим:** Закрытый клоз\n")
            .append("**Клозер:** ")
            .append(mention(hostId))
            .append('\n')
            .append("**Матч:** #")
            .append(number)
            .append('\n')
            .append("**Immortal draft:** ")
            .append(immortalDraft ? "да" : "нет")
            .append('\n')
            .append("**Gamemode:** ")
            .append(gamemodeLabel())
            .append('\n')
            .append("**Toxic:** ")
            .append(toxic ? "да 🔥" : "нет")
            .append('\n')
            .append("**Мест занято:** ")
            .append(participants.size())
            .append('/')
            .append(SLOTS)
            .append('\n')
            .append("**Участники**\n");

        if (immortalDraft) {
            // команды соберут капитаны — просто десять мест по порядку записи
            for (int slot = 0; slot < SLOTS; slot++) {
                appendSlot(text, slot, slotLabel(slot));
            }
        } else {
            // состав каждой команды — те, кто выбрал её при записи
            for (char team : new char[] { 'a', 'b' }) {
                text.append('\n')
                    .append(team == 'a' ? "🟦 **Команда A**" : "🟥 **Команда B**")
                    .append(" (")
                    .append(teamSize(team))
                    .append('/')
                    .append(TEAM_SIZE)
                    .append(")\n");
                List<Long> members = teamMembers(team);
                int offset = team == 'a' ? 0 : TEAM_SIZE;
                for (int i = 0; i < TEAM_SIZE; i++) {
                    appendSlot(
                        text,
                        offset + i,
                        i < members.size() ? mention(members.get(i)) : "Пусто"
                    );
                }
            }
        }
        return text.toString();
    }

    /** Строка слота: номер и ник (или «Пусто»). */
    private static void appendSlot(StringBuilder text, int slot, String who) {
        text.append('`')
            .append(slot + 1)
            .append('`')
            .append(' ')
            .append(who)
            .append('\n');
    }

    private String slotLabel(int slot) {
        if (slot >= participants.size()) {
            return "Пусто";
        }
        return mention(participants.get(slot));
    }

    /** Упоминание даёт синюю подсветку ника внутри Discord. */
    private static String mention(long discordId) {
        return "<@" + discordId + ">";
    }

    private String gamemodeLabel() {
        if (gamemode == null) {
            return "—";
        }
        return switch (gamemode.toLowerCase()) {
            case "cm" -> "Captains Mode";
            case "ap" -> "All Pick";
            default -> gamemode;
        };
    }
}
