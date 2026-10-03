package com.pocketsage.tournament.model;

/**
 * Игрок — строка таблицы {@code players}.
 *
 * <p>Ключ модели — {@code discordId}, а не {@code id}: Discord-аккаунт известен
 * всегда, а {@code id} появляется только после первой записи в БД (см.
 * {@link PlayerRepository#ensureId}). Поэтому в коде регулярно встречается
 * проверка {@code getId() == null} — это «игрок ещё не записан».
 *
 * <p>Класс — просто данные: правила валидации живут в репозитории и сервисах,
 * чтобы не размазывать их по геттерам.
 */
public class Player {

    // null до сохранения в БД — признак «ещё не сохранён», см. javadoc класса
    private Long id; // null до сохранения в БД
    private long discordId; // NOT NULL
    private Long steamId; // nullable, SteamID64
    private Long dotaAccountId; // nullable
    private String name; // NOT NULL
    private String steamName; // nullable, имя Steam-аккаунта из /bind
    private Integer mmr; // nullable
    private long winPoints; // NOT NULL DEFAULT

    public Player(
        long discordId,
        Long steamId,
        Long dotaAccountId,
        String name,
        String steamName,
        Integer mmr,
        long winPoints
    ) {
        this.discordId = discordId;
        this.steamId = steamId;
        this.dotaAccountId = dotaAccountId;
        this.name = name;
        this.steamName = steamName;
        this.mmr = mmr;
        this.winPoints = winPoints;
    }

    /** Игрок, про которого известны только Discord и имя Steam-аккаунта (/bind). */
    public Player(long discordId, String name, String steamName) {
        this(discordId, null, null, name, steamName, null, 0L);
    }

    // ===== mutators =====
    // нужны для маппинга из БД и для обновления привязки
    /** Проставляется репозиторием после INSERT — вручную не вызывается. */
    public void setId(Long id) {
        this.id = id;
    }

    /** Меняется только через /bind (см. SteamBindService). */
    public void setSteamName(String steamName) {
        this.steamName = steamName;
    }

    // ===== getters =====
    public Long getId() {
        return this.id;
    }

    public long getDiscordId() {
        return this.discordId;
    }

    public Long getSteamId() {
        return this.steamId;
    }

    public Long getDotaAccountId() {
        return this.dotaAccountId;
    }

    public String getName() {
        return this.name;
    }

    // Steam-ник подставляется вместо Discord-ника перед матчем; null, если
    // игрок не выполнял /bind — тогда MatchGameService ничего не подменяет.
    public String getSteamName() {
        return this.steamName;
    }

    public Integer getMmr() {
        return this.mmr;
    }

    public long getWinPoints() {
        return this.winPoints;
    }
}
