package com.pocketsage.tournament.bot.commands.close;

/**
 * Клоз — серия матчей.
 *
 * <p>Клоз создаётся один раз командой {@code /create_close_dota} с параметрами
 * из модалки; эти же параметры наследует каждый матч клоза. Матчи идут
 * последовательно, активный (собирающийся) один — {@link #getCurrentMatch()}.
 *
 * <p>id категории Discord — ключ реестра {@link CloseRegistrations} и часть
 * customId всех кнопок клоза.
 *
 * <p>Экземпляр меняется из потоков JDA (нажатия кнопок), поэтому методы
 * синхронизированы.
 */
public class CloseRegistration {

    private final long closeId; // id строки в таблице close
    private final String categoryId; // id категории Discord
    private final int number; // номер клоза
    private final long hostId; // discord id клозера
    private final boolean immortalDraft;
    private final String gamemode; // "cm" | "ap"
    private final boolean toxic;

    private CloseMatch currentMatch; // активный матч или null
    private int matchesStarted; // сколько матчей в клозе уже запускали

    /**
     * Где лежит сообщение «Матчи клоза» с кнопками управления.
     *
     * <p>Нужны, чтобы перерисовывать кнопки при смене состояния матча: они
     * публикуются один раз и без этих id мы не смогли бы их обновить.
     * Заполняются сразу после отправки сообщения, поэтому до этого null.
     */
    private Long controlChannelId;
    private Long controlMessageId;

    /**
     * Текст сообщения «Матчи клоза».
     *
     * <p>Discord при правке требует текст целиком (у JDA нет «поменять только
     * кнопки»), поэтому сохраняем его здесь — иначе пришлось бы
     * пересобирать текст заново и рисковать расхождением с тем, что видит
     * клозер прямо сейчас.
     */
    private String controlText;

    public CloseRegistration(
        long closeId,
        String categoryId,
        int number,
        long hostId,
        boolean immortalDraft,
        String gamemode,
        boolean toxic
    ) {
        this.closeId = closeId;
        this.categoryId = categoryId;
        this.number = number;
        this.hostId = hostId;
        this.immortalDraft = immortalDraft;
        this.gamemode = gamemode;
        this.toxic = toxic;
    }

    public long getCloseId() {
        return closeId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public int getNumber() {
        return number;
    }

    public long getHostId() {
        return hostId;
    }

    public boolean isImmortalDraft() {
        return immortalDraft;
    }

    public String getGamemode() {
        return gamemode;
    }

    public boolean isToxic() {
        return toxic;
    }

    /** Активный матч клоза или null, если матч ещё не запускали. */
    public synchronized CloseMatch getCurrentMatch() {
        return currentMatch;
    }

    /** Номер следующего матча внутри клоза: 1, 2, ... */
    public synchronized int nextMatchNumber() {
        return matchesStarted + 1;
    }

    /** Делает матч активным. */
    public synchronized void setCurrentMatch(CloseMatch match) {
        this.currentMatch = match;
        this.matchesStarted = Math.max(this.matchesStarted, match.getNumber());
    }

    /** Снимает активный матч — он доигран или удалён. */
    public synchronized void clearCurrentMatch() {
        this.currentMatch = null;
    }

    /** Запоминает, где опубликовано сообщение с кнопками управления матчем. */
    public synchronized void markControlMessage(
        long channelId,
        long messageId,
        String text
    ) {
        this.controlChannelId = channelId;
        this.controlMessageId = messageId;
        this.controlText = text;
    }

    public synchronized String getControlText() {
        return controlText;
    }

    public synchronized Long getControlChannelId() {
        return controlChannelId;
    }

    public synchronized Long getControlMessageId() {
        return controlMessageId;
    }

    /** Параметры модалки одним набором — их наследует каждый матч клоза. */
    public MatchSettings settings() {
        return new MatchSettings(gamemode, immortalDraft, toxic);
    }

    /** Параметры матча: режим, immortal draft, toxic. */
    public record MatchSettings(String gamemode, boolean immortalDraft, boolean toxic) {}
}