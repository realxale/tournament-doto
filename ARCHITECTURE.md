# Tournament Dota 2 — архитектура проекта

Discord-бот для организации клозов по Dota 2.
Стек: Java 21, Gradle (Kotlin DSL), JDA 6.5.0, PostgreSQL + Flyway, Logback, JUnit 5.

---

## Ключевая модель: клоз — это серия матчей

Это главное, что нужно понять перед чтением кода.

| | Клоз | Матч |
|---|---|---|
| Что это | Серия игр под одним клозером | Одна конкретная игра |
| Таблица | `close` | `match` |
| Состав игроков | **нет** — состав живёт в матче | `players_match` |
| Кнопка записи | — | «Записаться» / «Отписаться» |
| Жизненный цикл | `OPEN → FINISHED / CANCELLED` | `COLLECTING → PLAYING → FINISHED / CANCELLED` |

Клоз создаётся один раз командой `/create_close_dota`. Матчи запускаются
кнопками в канале «управление» и идут последовательно: активный матч всегда один.

---

## Старт игры: ники и голосовые

Кнопка **«Начать игру»** в «управление» (требует 10 участников) делает две вещи:

1. **Подменяет ники** участников на их Steam-ники (`players.steam_name` из `/bind`).
   Если игрок не делал `/bind`, его ник не трогаем — иначе он потерял бы имя
   до конца матча.
2. **Раскидывает игроков** по двум голосовым каналам согласно их командам.

Развилка по режиму:

| Режим | Что происходит |
|---|---|
| **обычный матч** | Команды заданы выбором игроков при записи (кнопки «В команду A/B») — сразу разводим по голосовым. |
| **immortal draft** | При записи команда не выбирается. Клозеру предлагается одна кнопка «Состав команды A», открывающая модалку с 5 селектами — по одному на слот. Discord не даёт больше 5 компонентов в модалке, поэтому всё состав A помещается в одну. **Команда B не выбирается**: она автоматически добирается из оставшихся пяти игроков, и матч стартует сразу после фиксации A. |

Явный выбор драфта важнее порядка записи: игрок, записанный десятым, может
оказаться в команде A.

Ники возвращаются при **завершении** матча, при **удалении** матча и при
**удалении клоза** — во всех трёх случаях до записи в БД, потому что возврат
читает сохранённые значения.

Нужные права бота: `MANAGE_NICKNAMES`, `MOVE_MEMBER` (в JDA 6 — `NICKNAME_MANAGE`
и `VOICE_MOVE_OTHERS`).

---

## Порядок работы

### 1. Создание клоза — `/create_close_dota`

1. `DotaCloseHandler` ловит команду, проверяет роль `Closemod`, показывает модалку
   (3 выпадающих списка: immortal draft, gamemode, toxic).
2. `DotaCloseService.handleSubmit()`:
   - перепроверяет права;
   - читает значения модалки;
   - проверяет, нет ли уже категории с таким именем;
   - создаёт категорию `dota 2 close by <клозер>` и 4 канала;
   - выставляет права на каналы;
   - пишет в БД строку `close` и **первый матч `#1`** с параметрами модалки;
   - отправляет в «управление» **два сообщения** (о клозе и о матчах).

> Карточка поиска игроков в «запись» при этом **не** отправляется —
> её создаёт кнопка «Создать матч».

### 2. Жизненный цикл матча — кнопки в «управление»

| Кнопка | Что делает |
|---|---|
| **Создать матч** | Публикует карточку «Поиск игроков — матч #N» в «запись» с кнопками записи. Если активного матча нет (предыдущий доигран/удалён) — сначала создаёт следующий по номеру. |
| **Начать игру** | Подменяет ники на Steam-ники и раскладывает игроков по голосовым `команда-а` / `команда-б`. Доступна, когда матч объявлен и собраны все 10 игроков. |
| **Завершить матч** | `status = FINISHED`, `time_end = now()`. Карточка в «запись» закрывается: кнопки убираются, остаётся итоговый состав. `result` остаётся `NULL` — победителя пока нет. |
| **Удалить матч** | `status = CANCELLED`, `time_end = now()`. Удаляются **все сообщения** канала «запись»: карточка матча и сообщения проверок готовности. |

### 3. Набор игроков — кнопки в «запись»

| Кнопка | Что делает |
|---|---|
| **Записаться** | Записывает в матч без выбора команды — только при immortal draft, где команды формирует клозер. |
| **В команду A / В команду B** | Запись в выбранную команду. В обычном матче игрок сам выбирает сторону; кнопка гаснет, когда в команде собралось 5 человек, но другая команда может быть ещё свободна. |
| **Отписаться** | Убирает игрока из состава и освобождает слот в его команде. |
| **Я готов** | Подтверждение в рамках запущенной проверки. |

Выбор команды при записи хранится в `CloseMatch.assignedTeams`, поэтому он
важнее порядка регистрации: первый записавшийся вполне может оказаться в
команде B. Именно этот выбор попадает в `players_match.team` и определяет
голосовой канал на старте игры.

### 4. Управление клозом

**Удалить клоз** — `status = CANCELLED`, `time_end = now()`, активный матч тоже
гасится, категория с каналами удаляется, клоз уходит из реестра.

Запись в БД не удаляется физически — остаётся история со временем конца.

### Права

- **Управлять матчами и клозом** — клозер или роль `Closemod`.
- **Записываться** — кто угодно, пока матч в статусе `COLLECTING`.
- Канал «управление» закрыт от обычных участников на уровне Discord,
  «запись» — только на чтение для всех.

### Структура каналов

```
Категория: dota 2 close by <клозер>
├── чат-клоз      (текст)  — общение участников
├── управление    (текст)  — 🔒 только Closemod: сообщения клоза и матчей + кнопки
├── запись        (текст)  — карточка поиска игроков + кнопки записи
├── команда-а     (голос)  — голосовой канал команды A
├── команда-б     (голос)  — голосовой канал команды B
└── ожидание      (голос)  — участники ждут старта матча и возвращаются сюда после финала
```

---

## Файлы проекта

### Конфигурация и сборка

| Файл | Что делает |
|---|---|
| `build.gradle.kts` | Плагины `application`, `java` (toolchain 21), `flyway`. Зависимости: JDA 6.5.0, Jackson, PostgreSQL + HikariCP, Flyway 10.17.0, Logback, dotenv-java. Тесты: JUnit 5.11, Testcontainers. Main-класс: `com.pocketsage.tournament.Main`. |
| `settings.gradle.kts` | Имя Gradle-проекта. |
| `gradlew`, `gradlew.bat`, `gradle/` | Gradle Wrapper — сборка без установленного Gradle. |
| `env.env` | Шаблон конфига в репозитории: `DISCORD_TOKEN`, `DATABASE_URL`, `DATABASE_LOGIN`, `DATABASE_PASSWORD`, `OPENDOTA_APIKEY`. |
| `.env` | Реальные секреты, в git не попадают. Читается вместо `env.env`, если существует. |

### Точка входа

#### `Main.java`
Читает конфиг, создаёт `BotLauncher`, вешает shutdown-hook (гасит JDA и пул БД),
запускает. Ошибки токена и конфига — код выхода `1`.

#### `bot/BotLauncher.java`
Последовательность запуска: `EnvLoader` → `Database.migrate()` (Flyway) →
`JDABuilder` с четырьмя слушателями → `awaitReady()`.
`stop()` закрывает JDA и HikariCP.

### Конфигурация

#### `config/EnvLoader.java`
Читает `.env`, иначе `env.env`. `require()` бросает `IllegalStateException` на
пустой переменной. Геттеры: `getDiscordToken()`, `getDatabaseUrl()`,
`getDatabaseLogin()`, `getDatabasePassword()`, `getOpenDotaApiKey()` (опциональный).

### База данных

#### `repository/Database.java`
Обёртка над HikariCP: пул 20/2, таймаут 5 с, проверка 3 с. Приватный конструктор —
боевой (конфиг из `EnvLoader`), публичный с JDBC-URL — для тестов.
`migrate()` гоняет Flyway по `classpath:db/migration`. Синглтон `getInstance()`.

#### `repository/PlayerRepository.java`
CRUD игроков: `save`, `findByDiscordId`, `update`, `delete`.
**`ensureId(discordId, name)`** — находит игрока по Discord-аккаунту или создаёт
строку при первом обращении. Нужен там, где на игрока ссылаются другие таблицы
(`close.owner_id`, `players_match.player_id`): без `/bind` внешний ключ не проходит.

#### `repository/CloseRepository.java`
Клозы (`close`). `create(...)` — `status = OPEN`, `number` из `close_number_seq`,
`time_start` из БД; возвращает `record Created(long id, int number)`.
Также `cancel`, `finish`, `delete`, `findByDiscordCategory`.

#### `repository/MatchRepository.java`
Матчи (`match`) и состав (`players_match`). `create(...)` — матч в статусе
`COLLECTING`. `addParticipant(...)` — **upsert** состава (`ON CONFLICT DO UPDATE`):
повторный вызов меняет команду, не плодя дубли.
`removeParticipant`, `finish(matchId, winner)` (`winner = null` → `result` остаётся
`NULL`), `cancel`, `delete`.

#### `model/Player.java`
POJO игрока: `id`, `discordId`, `steamId`, `dotaAccountId`, `name`, `steamName`,
`mmr`, `winPoints`. Сеттеры нужны для маппинга из БД.

### Миграции

| Файл | Что добавляет |
|---|---|
| `V1__init.sql` | Начальная схема: `players`, `player_roles`, `close`, `close_participant`, `match`, `players_match`, `bet_logs`, `points_tx` + индексы. `CHECK`-ограничения на статусы и на `players_match.team IN ('a','b')`. |
### Служебные команды

#### `bot/commands/CommandRegistry.java`
На `ReadyEvent` регистрирует `/help`, `/info`, `/create_close_cs` (заглушка под CS).

#### `bot/commands/SteamBindHandler.java`
`/bind`: модалка с полем «Steam ID» (1–32 символа). Игрок вводит только
идентификатор — ник, ранг и MMR бот добирает сам через OpenDota, поэтому руками
вводить нечего. `deferReply(true)` обязателен: модалку надо подтвердить за 3 секунды,
а HTTP-запрос к API столько может не занять.

Различает четыре исхода и отвечает по-разному: неверный формат id
(`IllegalArgumentException`), аккаунт не найден или профиль закрыт
(`SteamProfileException`), сеть недоступна (`IOException`) — в последних двух
детали уходят в лог, игроку только текст; и успешная запись. При `SQLException`
подсказывает вероятную причину: `steam_id` в БД `UNIQUE`, то есть аккаунт мог
быть уже привязан к другому профилю.

Собирает строку ответа через `buildSuccessMessage`: ник, MMR и название ранга
(`rankName` переводит 1–8 в Herald…Immortal).

#### `bot/commands/OpenDotaClient.java`
Клиент OpenDota на `java.net.http.HttpClient` + Jackson. Ключ из
`OPENDOTA_APIKEY` подставляется в query только если он непустой — **API работает и
без ключа**, просто с общим низким лимитом.

- `parseAccountId(String)` — чистая функция, поэтому тестируется без сети.
  Принимает `account_id`, SteamID64 (вычитается база 76561197960265728),
  `STEAM_0:Y:Z` (формула `Z*2 + Y`) и ссылку с числовым хвостом. Vanity-имена
  (`steamcommunity.com/id/имя`) **отклоняются**: превратить их в `account_id`
  OpenDota не умеет, поэтому честная ошибка лучше выдуманного id.
- `mapProfile(JsonNode, long)` — тоже чистая функция. Ответ OpenDota неоднороден:
  профиль лежит в объекте `profile`, а `rank_tier` и `computed_mmr` — на верхнем
  уровне, причём у закрытых профилей верхнего уровня может не быть. Поле `name`
  предпочтительнее `personaname` — это ник в Steam, а не ник в игре.
- `send(HttpRequest)` — обёртка: `InterruptedException` сворачивается в
  `IOException` **сохраняя флаг прерывания**, иначе отмена задачи JDA перестала бы
  работать.

Внутренние `SteamProfile` (record) и `SteamProfileException` (extends IOException)
живут здесь же: исключение — `IOException`, потому что это сетевая ошибка, но
сообщение у него безопасно для показа игроку.

#### `bot/commands/SteamBindOldHandler.java`
`/bind_old`: прежний `/bind`, переименованный без изменения логики (поменялись
только имя команды и id модалки — `steam_bind_old_modal`). Нужен там, где
OpenDota ничего не знает: профиль закрыт настройками приватности или игрок хочет
задать ник вручную.

#### `bot/commands/SteamBindService.java`
Бизнес-логика привязки: нет игрока → создать, есть → обновить.
`players.name` — `NOT NULL`, поэтому при пустом Discord-имени берётся `steam_name`
(а если и его нет, в `nameOrDefault` есть последний фолбэк `"player"`).
Возвращает `record BindResult(Player player, boolean created)`.

Два метода записи:
- `bind(...)` — для `/bind_old`, обновляет только `steam_name`;
- `bindProfile(...)` — для `/bind`, пишет `steam_id`, `dota_account_id`,
  `steam_name` и `mmr`.

MMR обновляется **только если OpenDota его отдала**: у закрытых профилей оценки
нет, и затирать уже сохранённую нельзя — поэтому в `bindProfile` стоит отдельная
проверка на `null`. Конструктор с готовым репозиторием — для тестов.

### Клоз и матчи — `bot/commands/close/`

#### `DotaCloseHandler.java`
Точка входа в создание клоза. `onReady` → регистрирует `/create_close_dota`.
Проверяет роль `Closemod` (регистронезависимо), показывает модалку `dota_close_modal`
с тремя `StringSelectMenu`. `onModalInteraction` → `service.handleSubmit(event)`.

#### `DotaCloseService.java`
Создание клоза:
- `handleSubmit(event)` — полный сценарий: права → значения модалки →
  проверка дубликата → категория → каналы → права → БД → сообщения.
  Использует `deferReply`, потому что Discord даёт 3 секунды.
- `createFirstMatch(registration)` — матч `#1` с параметрами модалки.
- `writeControlMessages(ch, registration)` — два сообщения в «управление»:
  первое о клозе (параметры + «Удалить клоз»), второе о матчах (три кнопки).
- `applyPermissions(ch, guild)` — «управление» только для `Closemod`,
  «запись» на чтение для всех, боту выдаются права на запись и историю.
- record `CloseChannels(category, chat, control, log, voiceA, voiceB, waiting)`
  — контейнер каналов.

#### `CloseRegistration.java` — **клоз**
Мета клоза: `closeId` (БД), `categoryId` (Discord), `number`, `hostId`, параметры
модалки. Хранит `currentMatch` (активный матч или `null`) и `matchesStarted`.
Методы: `nextMatchNumber()`, `setCurrentMatch()`, `clearCurrentMatch()`,
`settings()` → `record MatchSettings(gamemode, immortalDraft, toxic)` —
параметры, которые наследует каждый матч. Методы `synchronized`.
#### `CloseMatch.java` — **матч**
Состояние одного матча: `matchId` (БД), `closeId`, `closeCategoryId`, `number`,
`hostId`, параметры. Состав — `List<Long> participants`, подтверждения —
`Set<Long> ready`.
- `signUp()` / `signOff()` — запись принимается только в статусе `COLLECTING`;
- `isFull()`, `size()`, `teamOf(discordId)` — команда по позиции в записи;
- `markFinished()` / `markCancelled()` — переводят в конечный статус;
- `isAnnounced()` / `markAnnounced()` — опубликована ли карточка в «запись»;
- `markSignupMessage()` — запоминает канал и сообщение карточки, чтобы
  обновлять её не только из нажатий;
- `render()` — карточка поиска игроков (слоты, команды, счётчик);
- `renderResult()` — закрытая карточка доигранного матча;
- `renderReadiness(deadline)` / `renderReadinessResult(kicked)` — проверка готовности;
- `confirmReady()`, `isReady()`, `notReady()`, `signOffNotReady()`.

#### `CloseRegistrations.java`
Реестр активных клозов `ConcurrentHashMap<String, CloseRegistration>`.
**Ключ — id категории Discord**: он переживает перезапуск бота (в отличие от
счётчиков) и всегда короче лимита Discord в 100 символов на `custom_id`.
Константы действий, фабрики кнопок (`buttons`, `matchControlButtons`,
`closeControlButtons`, `readyButtons`), `buttonId(action, categoryId)` и
`refreshSignupCard(...)` — перерисовка карточки, когда нажатия не было
(например, по таймеру выписали игроков).

Состояния кнопок зависят от контекста: «Создать матч» гаснет после публикации
карточки, «Завершить матч» активна только на сборе, «Удалить матч» — когда
матч существует.

#### `CloseRegistrationHandler.java`
Единая точка обработки всех кнопок. Разбор `customId`: `actionOf()` возвращает
действие или `null` (кнопка не наша), `categoryIdOf()` — id клоза.

Маршрутизация:
- `handleSignup` / `handleSignOff` — состав: память + `players_match` + перерисовка;
- `handleReadyStart` — запуск проверки готовности матча;
- `handleReady` — подтверждение «Я готов»;
- `handleMatchStart` — публикация карточки, при необходимости создание матча;
- `handleMatchDelete` — `CANCELLED` + очистка канала «запись» от всех сообщений;
- `handleMatchFinish` — `FINISHED` + закрытие карточки;
- `handleCloseDelete` — `CANCELLED` у клоза и матча + удаление категории;
- `handleGameStart` — развилка: драфт или сразу разводка по голосовым;
- `handleDraft` / `handleDraftSubmit` — состав команды A при immortal draft,
  затем автозаполнение B остатком и старт игры;
- `beginGame` / `endGame` — подмена и возврат ников, работа с голосовыми.

Вспомогательное: `requireModerator` (клозер или `Closemod`), `requireCollectingMatch`,
`persistParticipant` (состав в БД; ошибка БД не отменяет запись в Discord),
`announceMatch`, `findSignupChannel`, `deleteSignupCard`, `lockSignupCard`.

Планировщик `close-readiness` — daemon-поток, создаётся в конструкторе по
умолчанию; в тестах передаётся свой `ScheduledExecutorService`.

#### `MatchGameService.java`
Старт и финиш игры. `startGame(match, voiceA, voiceB)` переводит матч в
`PLAYING`, подменяет ники и разводит игроков по голосовым; возвращает
`record Report(...)` с отчётом для клозера. `endGame(match, waiting)` возвращает ники
по сохранённым значениям, чистит их в БД и переводит всех участников в канал
ожидания. `moveToWaiting(discordId, waiting)` отправляет записавшегося игрока
ждать матч. Ник запоминается **до** подмены —
иначе вернуть было бы нечего.

#### `DraftModal.java`
Модалка выбора состава **команды A** при immortal draft: Discord не даёт больше
5 компонентов, поэтому пять селектов (по одному на слот) — это ровно один состав.
id модалки `dota_draft:<team>:<categoryId>`, `teamOf(modalId)` и
`categoryIdOf(modalId)` разбирают его назад в действие и клоз. В вариантах
селектов стоит ник участника (`displayName`), а `value` — его id.

#### `CloseReadinessCheck.java`
Проверка готовности **внутри матча**. Окно `WINDOW` = 3 минуты.
`start(match, channel, scheduler)` сбрасывает прошлые подтверждения, отправляет
сообщение с кнопкой «Я готов» и ставит таймер. По истечении `signOffNotReady()`
выписывает молчащих, закрывает сообщение проверки и обновляет карточку матча.
Реестр проверок — по `matchId`, поэтому проверки разных матчей не путаются.

### Тесты

| Файл | Что проверяет |
|---|---|
| `close/CloseMatchTest.java` | Состав матча: дубликаты, 11-й игрок, команды, слоты, `COLLECTING`/`FINISHED`, проверка готовности, выписка молчащих, рендер карточек, лимиты Discord. |
| `close/CloseRegistrationTest.java` | Клоз как серия матчей: нумерация матчей, наследование параметров модалки, реестр, состояния кнопок управления. |
| `close/CloseMessagesTest.java` | `custom_id` кнопок в лимите Discord, маршрутизация нажатий, отсутствие путаницы `match_ready` / `close_ready`, длина сообщений. |
| `SteamBindServiceTest.java` | Привязка Steam (требует Docker для Testcontainers). |
| `OpenDotaClientTest.java` | Разбор Steam ID (account_id, SteamID64, `STEAM_0:Y:Z`, ссылка, мусор) и разбор ответа OpenDota (данные есть, только personaname, закрытый профиль, ошибка). Сеть не трогается — обе проверяемые функции чистые. |

---

## Сборка и запуск

```bash
./gradlew build      # компиляция + тесты
./gradlew test       # только тесты
./gradlew run        # запуск бота (нужны .env и доступная БД)
```

Что нужно для запуска:
1. Роль `Closemod` на сервере Discord (иначе клозы не создаются).
2. PostgreSQL — Flyway сам накатит `V1`–`V4` при первом запуске.
3. `env.env` (или `.env`) с `DISCORD_TOKEN` и параметрами БД.
4. Доступ в интернет — `/bind` ходит в OpenDota. `OPENDOTA_APIKEY` необязателен:
   без него API работает с общим низким лимитом.

---

## Ограничения и что дальше

- **Состав живёт в памяти.** `CloseRegistrations` очищается при рестарте бота;
  в БД остаются состав и времена. Восстановление активного матча из
  `close`/`match` при старте пока не реализовано.
- **`match.result` остаётся `NULL`** — победителя нужно записывать отдельно.
- **SQL-запросы репозиториев не покрыты тестами** (нужен Docker для Testcontainers):
  проверялись вручную на локальном Postgres через `PREPARE`.
- Каталог под git (`master`, релиз `v1.0`). Функциональное описание и команды — в `README.md`.
- `/create_close_cs` зарегистрирован, но обработчика нет.
| `V2__player_steam_name.sql` | `players.steam_name` (имя из `/bind`, SteamID64 позже) + индекс `lower(steam_name)`. |
| `V3__close_match_params.sql` | Разделяет клоз и матч: `close` (+`discord_category_id` UNIQUE, параметры модалки, `close_number_seq`, `DEFAULT now()`), `match` (+`number`, `status`, копия параметров), уникальный индекс `(close_id, number)`, индекс по `status`. |
| `V4__match_nicknames.sql` | Обратимая подмена ников: `players_match` (+`original_nickname`, +`nickname_saved`). Флаг нужен отдельно от `NULL`: исходный ник может быть законно `null` (у игрока не было ника на сервере), и по одному `NULL` нельзя отличить «не сохраняли» от «сохранили пустое». |

> ⚠️ **Грабли, на которые уже наступали.** Порядок колонок в `INSERT` и порядок
> `ps.setXxx()` обязан совпадать, а литерал в `VALUES` (например `'OPEN'`)
> **сдвигает нумерацию плейсхолдеров**. Из-за этого строка попадает в boolean-колонку:
> `column "immortal_draft" is of type boolean but expression is of type character varying`.
> Поэтому в `CloseRepository` статус передаётся параметром (`STATUS_OPEN`),
> а не литералом. Перед изменением любого `INSERT` проверяй соответствие —
> удобно через `PREPARE name(types...) AS <query>`: PostgreSQL сразу скажет
> о расхождении.
```