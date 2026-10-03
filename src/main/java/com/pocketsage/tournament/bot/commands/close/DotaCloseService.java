package com.pocketsage.tournament.bot.commands.close;

import com.pocketsage.tournament.repository.CloseRepository;
import com.pocketsage.tournament.repository.MatchRepository;
import com.pocketsage.tournament.repository.PlayerRepository;
import java.sql.SQLException;
import java.util.List;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Создание клоза Dota 2.
 *
 * <p>Клоз — серия матчей. При создании в БД появляются строка {@code close}
 * и первый матч {@code match} с параметрами из модалки, но карточка поиска
 * игроков в «запись» не отправляется: её создаёт кнопка «Создать матч».
 */
public class DotaCloseService {

    private static final Logger log = LoggerFactory.getLogger(
        DotaCloseService.class
    );

    private final CloseRepository closes;
    private final MatchRepository matches;
    private final PlayerRepository players;

    public DotaCloseService() {
        this(new CloseRepository(), new MatchRepository(), new PlayerRepository());
    }

    /** Конструктор с готовыми репозиториями — нужен тестам. */
    public DotaCloseService(
        CloseRepository closes,
        MatchRepository matches,
        PlayerRepository players
    ) {
        this.closes = closes;
        this.matches = matches;
        this.players = players;
    }

    /** Голосовой канал команды A. */
    public static final String VOICE_A = "команда-а";

    /** Голосовой канал команды B. */
    public static final String VOICE_B = "команда-б";

    /**
     * Голосовой канал ожидания: участники ждут здесь начала матча и возвращаются
     * сюда после его окончания.
     */
    public static final String VOICE_WAITING = "ожидание";

    // ===== Контейнер каналов одного клоза =====
    public record CloseChannels(
        Category category,
        TextChannel chat,
        TextChannel control,
        TextChannel log,
        VoiceChannel voiceA,
        VoiceChannel voiceB,
        VoiceChannel waiting
    ) {}

    // ===== Обработка Submit модалки =====
    public void handleSubmit(ModalInteractionEvent event) {
        // 1. Перепроверка прав
        Member member = event.getMember();
        if (member == null) {
            event.reply("Только на сервере.").setEphemeral(true).queue();
            return;
        }
        boolean hasRole = member
            .getRoles()
            .stream()
            .anyMatch(r -> r.getName().equalsIgnoreCase("Closemod"));
        if (!hasRole) {
            event.reply("❌ Нужна роль Closemod.").setEphemeral(true).queue();
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Только на сервере.").setEphemeral(true).queue();
            return;
        }

        // 2. Достаём значения (все три поля — StringSelectMenu, читаем как список)
        String immortal, draft, toxic;
        try {
            List<String> immortalList = event
                .getValue("immortal")
                .getAsStringList();
            List<String> draftList = event.getValue("draft").getAsStringList();
            List<String> toxicList = event.getValue("toxic").getAsStringList();

            if (
                immortalList.isEmpty() ||
                draftList.isEmpty() ||
                toxicList.isEmpty()
            ) {
                event
                    .reply("❌ Ошибка чтения модалки.")
                    .setEphemeral(true)
                    .queue();
                return;
            }

            immortal = immortalList.get(0);
            draft = draftList.get(0);
            toxic = toxicList.get(0);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            event.reply("❌ Ошибка чтения модалки.").setEphemeral(true).queue();
            return;
        }

        // 3. Имена
        String displayName = member.getEffectiveName();
        String categoryName = "dota 2 close by " + displayName;
        if (categoryName.length() > 100) {
            categoryName = categoryName.substring(0, 100);
        }
        String chatName = "чат-клоз";

        final String finalCategoryName = categoryName;

        // 4. Defer — у нас 3 секунды
        event.deferReply(true).queue(hook -> {
            // 5. Проверка на дубликат
            if (!guild.getCategoriesByName(finalCategoryName, true).isEmpty()) {
                hook.sendMessage("❌ Клоз уже существует.").queue();
                return;
            }

            // 6. Создаём категорию
            guild.createCategory(finalCategoryName).queue(
                category -> {
                    // 7. Создаём каналы параллельно
                    // <Object> нужен явно: javac не может вывести общий тип из
                    // ChannelAction<TextChannel> и ChannelAction<VoiceChannel>
                    RestAction.<Object>allOf(
                        category.createTextChannel(chatName),
                        category.createTextChannel("управление"),
                        category.createTextChannel("запись"),
                        category.createVoiceChannel(VOICE_A),
                        category.createVoiceChannel(VOICE_B),
                        // участники ждут начала матча здесь
                        category.createVoiceChannel(VOICE_WAITING)
                    ).queue(
                        results -> {
                            CloseChannels ch = new CloseChannels(
                                category,
                                (TextChannel) results.get(0),
                                (TextChannel) results.get(1),
                                (TextChannel) results.get(2),
                                (VoiceChannel) results.get(3),
                                (VoiceChannel) results.get(4),
                                (VoiceChannel) results.get(5)
                            );

                            // 8. Права
                            applyPermissions(ch, guild);

                            boolean isImmortal = "y".equalsIgnoreCase(immortal);
                            boolean isToxic = "y".equalsIgnoreCase(toxic);
                            String categoryId = category.getId();

                            // 9. Клоз и первый матч — в БД.
                            // Параметры матча наследуются из модалки клоза.
                            try {
                                long ownerPlayerId = players.ensureId(
                                    member.getIdLong(),
                                    displayName
                                );
                                CloseRepository.Created close = closes.create(
                                    ownerPlayerId,
                                    categoryId,
                                    draft,
                                    isImmortal,
                                    isToxic
                                );
                                CloseRegistration registration =
                                    new CloseRegistration(
                                        close.id(),
                                        categoryId,
                                        close.number(),
                                        member.getIdLong(),
                                        isImmortal,
                                        draft,
                                        isToxic
                                    );
                                CloseRegistrations.add(registration);
                                createFirstMatch(registration);

                                // 10. В «управление»: инфо о клозе + управление матчем
                                writeControlMessages(ch, registration);

                                hook.sendMessage(
                                    "✅ Клоз создан: " + category.getAsMention()
                                ).queue();
                            } catch (SQLException e) {
                                log.error("Не удалось сохранить клоз в БД", e);
                                hook.sendMessage("❌ Ошибка БД: " + e.getMessage()).queue();
                            }
                        },
                        err ->
                            hook
                                .sendMessage(
                                    "❌ Ошибка каналов: " + err.getMessage()
                                )
                                .queue()
                    );
                },
                err ->
                    hook
                        .sendMessage("❌ Ошибка категории: " + err.getMessage())
                        .queue()
            );
        });
    }

    /**
     * Первый матч клоза создаётся сразу, но карточку поиска игроков в «запись»
     * не отправляем — её создаёт кнопка «Создать матч».
     */
    private void createFirstMatch(CloseRegistration registration)
        throws SQLException {
        CloseRegistration.MatchSettings settings = registration.settings();
        int number = registration.nextMatchNumber();
        long matchId = matches.create(
            registration.getCloseId(),
            number,
            settings.gamemode(),
            settings.immortalDraft(),
            settings.toxic()
        );
        registration.setCurrentMatch(
            new CloseMatch(
                matchId,
                registration.getCloseId(),
                registration.getCategoryId(),
                number,
                registration.getHostId(),
                settings.immortalDraft(),
                settings.gamemode(),
                settings.toxic()
            )
        );
    }

    // ===== Сообщения канала «управление» =====
    private void writeControlMessages(
        CloseChannels ch,
        CloseRegistration registration
    ) {
        String categoryId = registration.getCategoryId();

        // Сообщение 1: сам клоз — параметры модалки и управление клозом.
        ch.control()
            .sendMessage(
                """
                🎮 **Клоз Dota 2 #%d**
                Клозер: <@%d>
                Immortal draft: %s
                Gamemode: %s
                Toxic: %s

                Клоз — серия матчей. Матчи запускаются кнопками ниже.
                Состав набирается в канале %s.
                """.formatted(
                    registration.getNumber(),
                    registration.getHostId(),
                    registration.isImmortalDraft() ? "да" : "нет",
                    gamemodeLabel(registration.getGamemode()),
                    registration.isToxic() ? "да 🔥" : "нет",
                    ch.log().getAsMention()
                )
            )
            .setComponents(CloseRegistrations.controlButtons(categoryId))
            .queue();

        // Сообщение 2: управление матчами — создать / проверить / начать игру.
        // Текст собираем в переменную: он понадобится и сейчас, и при
        // каждой перерисовке кнопок (Discord требует текст целиком).
        // Сборка текста живёт в CloseRegistrations, чтобы обе точки совпадали.
        String matchControlText = CloseRegistrations.matchControlText(
            registration,
            ch.log()
        );

        ch.control()
            .sendMessage(matchControlText)
            .setComponents(CloseRegistrations.matchControlButtons(registration))
            .queue(
                // запоминаем id: кнопки перерисовываются при смене состояния матча
                message ->
                    registration.markControlMessage(
                        ch.control().getIdLong(),
                        message.getIdLong(),
                        matchControlText
                    ),
                error ->
                    log.error("Не удалось отправить сообщение «Матчи клоза»", error)
            );
    }

    private static String gamemodeLabel(String gamemode) {
        if (gamemode == null) {
            return "—";
        }
        return switch (gamemode.toLowerCase()) {
            case "cm" -> "Captains Mode";
            case "ap" -> "All Pick";
            default -> gamemode;
        };
    }

    // ===== Права на каналы =====
    private void applyPermissions(CloseChannels ch, Guild guild) {
        Role publicRole = guild.getPublicRole();
        Role closemodRole = guild
            .getRolesByName("Closemod", true)
            .stream()
            .findFirst()
            .orElse(null);

        if (closemodRole == null) {
            System.err.println(
                "⚠️ Роль Closemod не найдена — права не настроены!"
            );
            return;
        }

        // ===== УПРАВЛЕНИЕ: только Closemod =====
        ch.control()
            .upsertPermissionOverride(publicRole)
            .deny(Permission.VIEW_CHANNEL)
            .queue();
        ch.control()
            .upsertPermissionOverride(closemodRole)
            .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
            .queue();

        // ===== ЗАПИСЬ: видят все, пишет только Closemod =====
        ch.log()
            .upsertPermissionOverride(publicRole)
            .grant(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_HISTORY,
                Permission.MESSAGE_ADD_REACTION
            )
            .deny(Permission.MESSAGE_SEND)
            .queue();
        ch.log()
            .upsertPermissionOverride(closemodRole)
            .grant(Permission.MESSAGE_SEND)
            .queue();

        // ===== бот должен сам писать и обновлять сообщения клоза =====
        ch.log()
            .upsertPermissionOverride(guild.getSelfMember())
            .grant(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_HISTORY
            )
            .queue();
        ch.control()
            .upsertPermissionOverride(guild.getSelfMember())
            .grant(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_HISTORY
            )
            .queue();

        // ===== ГОЛОСОВЫЕ ПО КОМАНДАМ =====
        // участники должны видеть оба канала и иметь право подключиться,
        // иначе бот не сможет их развести при старте игры
        for (
            VoiceChannel voice : new VoiceChannel[] {
                ch.voiceA(),
                ch.voiceB(),
                ch.waiting()
            }
        ) {
            if (voice == null) {
                continue;
            }
            voice
                .upsertPermissionOverride(publicRole)
                .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
                .queue();
            voice
                .upsertPermissionOverride(closemodRole)
                .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_MOVE_OTHERS)
                .queue();
            // боту нужно право двигать участников между голосовыми
            voice
                .upsertPermissionOverride(guild.getSelfMember())
                .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_MOVE_OTHERS)
                .queue();
        }
    }
}
