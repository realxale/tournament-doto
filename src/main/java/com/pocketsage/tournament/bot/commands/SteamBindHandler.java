package com.pocketsage.tournament.bot.commands;

import com.pocketsage.tournament.bot.commands.OpenDotaClient.SteamProfile;
import com.pocketsage.tournament.bot.commands.OpenDotaClient.SteamProfileException;
import com.pocketsage.tournament.config.EnvLoader;
import java.io.IOException;
import java.sql.SQLException;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Команда {@code /bind}: привязка Steam-аккаунта по его ID.
 *
 * <p>Игрок вводит Steam ID (или SteamID64, или {@code STEAM_0:...}), а бот
 * сам добирает ник, ранг и MMR через OpenDota API и записывает их в
 * {@code players}. Вводить руками, кроме самого ID, ничего не нужно.
 *
 * <p>Смысл в том, чтобы ник в игре совпадал с ником в Dota: подмена ника
 * участника на матче берёт {@code players.steam_name}, поэтому важно, чтобы
 * там лежало настоящее имя, а не то, как игрок назвал себя в Discord.
 *
 * <p>Если OpenDota ничего не знает об аккаунте (закрытый профиль, нет
 * публичных матчей), остаётся ручной вариант — {@code /bind_old}.
 *
 * <p>Класс только принимает ввод и показывает результат: разбор ID, поход в
 * API и запись — в {@link OpenDotaClient} и {@link SteamBindService}.
 */
public class SteamBindHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SteamBindHandler.class);

    /**
     * SteamID64 — 17 цифр, но с запасом: принимаем и «голый» account_id.
     */
    private static final int MAX_STEAM_ID = 32;

    private final SteamBindService service = new SteamBindService();

    private final OpenDotaClient openDota;

    public SteamBindHandler() {
        this(new OpenDotaClient(new EnvLoader().getOpenDotaApiKey()));
    }

    /** Конструктор с готовым клиентом — нужен тестам. */
    public SteamBindHandler(OpenDotaClient openDota) {
        this.openDota = openDota;
    }

    // ===== Регистрация команды =====
    @Override
    public void onReady(ReadyEvent event) {
        event.getJDA()
            .upsertCommand("bind", "Привязать Steam-аккаунт по Steam ID (через OpenDota)")
            .queue();
    }

    // ===== Ловля /bind =====
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("bind")) return;
        showBindModal(event);
    }

    // ===== Модалка =====
    /** Показывает форму ввода Steam ID. */
    private void showBindModal(SlashCommandInteractionEvent event) {
        // id поля steam_id читается в handleSubmit — связаны между собой.
        TextInput steamId = TextInput.create("steam_id", TextInputStyle.SHORT)
            .setPlaceholder("70388657 или 76561198030654385")
            .setRequired(true)
            .setMinLength(1)
            .setMaxLength(MAX_STEAM_ID)
            .build();

        // id модалки должен совпадать с проверкой в onModalInteraction ниже.
        Modal modal = Modal.create("steam_bind_modal", "Привязка Steam-аккаунта")
            .addComponents(Label.of("Steam ID", steamId))
            .build();

        event.replyModal(modal).queue();
    }

    // ===== Submit модалки =====
    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        // Сюда прилетают submit'ы от всех модалок бота.
        if (!event.getModalId().equals("steam_bind_modal")) return;
        handleSubmit(event);
    }

    /**
     * Разрешает введённый ID через OpenDota и сохраняет профиль.
     *
     * <p>{@code deferReply} обязателен: модалку нужно подтвердить за 3 секунды.
     */
    private void handleSubmit(ModalInteractionEvent event) {
        long discordId = event.getUser().getIdLong();
        // Имя берём на момент привязки: players.name — «как видели в тот
        // момент», а не «как зовут сейчас».
        String discordName = event.getUser().getEffectiveName();

        String rawId;
        try {
            rawId = event.getValue("steam_id").getAsString().trim();
        } catch (IllegalArgumentException e) {
            // такого поля в модалке нет — значит это submit чужой формы
            event.reply("❌ Ошибка чтения модалки.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook -> resolveAndSave(hook, discordId, discordName, rawId));
    }
/** Сеть и БД вынесены из лямбды, чтобы стек читался без прыжков. */
    private void resolveAndSave(
        net.dv8tion.jda.api.interactions.InteractionHook hook,
        long discordId,
        String discordName,
        String rawId
    ) {
        SteamProfile profile;
        try {
            profile = openDota.resolve(rawId);
        } catch (IllegalArgumentException e) {
            // неверный формат id — вина игрока, сообщаем прямо
            hook.sendMessage("❌ " + e.getMessage()).queue();
            return;
        } catch (SteamProfileException e) {
            hook.sendMessage("❌ " + e.getMessage()
                + "\nЕсли профиль закрыт — используй `/bind_old`.").queue();
            return;
        } catch (IOException e) {
            // детали — только в лог, чтобы не светить внутренности наружу
            log.error("Не удалось получить профиль Steam (discord_id={})", discordId, e);
            hook.sendMessage("❌ OpenDota недоступна, попробуй позже.").queue();
            return;
        }

        if (!profile.hasSteamName()) {
            hook.sendMessage(
                "⚠️ Профиль найден, но ник скрыт настройками приватности — "
                    + "подменять нечего. Укажи ник вручную через `/bind_old`.")
                .queue();
            return;
        }

        try {
            SteamBindService.BindResult result =
                service.bindProfile(discordId, discordName, profile);

            hook.sendMessage(buildSuccessMessage(profile, result.created())).queue();
        } catch (SQLException e) {
            log.error("Не удалось сохранить привязку Steam (discord_id={})", discordId, e);
            // UNIQUE по steam_id — самый вероятный исход: аккаунт уже привязан
            hook.sendMessage(
                "❌ Не удалось сохранить привязку. "
                    + "Возможно, этот Steam-аккаунт уже привязан к другому профилю.")
                .queue();
        }
    }

    /** Итог привязки: ник, ранг и MMR — то, ради чего бот ходил в OpenDota. */
    static String buildSuccessMessage(SteamProfile profile, boolean created) {
        StringBuilder message = new StringBuilder()
            .append("✅ Steam привязан: **")
            .append(profile.steamName())
            .append("**")
            .append(created ? "\n🎮 Профиль создан в базе." : "\n♻️ Профиль обновлён.");

        if (profile.mmr() != null) {
            message.append("\n📊 MMR: ").append(profile.mmr());
        }
        if (profile.rankTier() != null) {
            message.append(" · ранг: ").append(rankName(profile.rankTier()));
        }
        return message.toString();
    }

    /** Подписи рангов те же, что у Discord: 1 Herald … 8 Immortal. */
    static String rankName(int rankTier) {
        return switch (rankTier) {
            case 1 -> "Herald";
            case 2 -> "Guardian";
            case 3 -> "Crusader";
            case 4 -> "Archon";
            case 5 -> "Legend";
            case 6 -> "Ancient";
            case 7 -> "Divine";
            case 8 -> "Immortal";
            default -> "неизвестный (" + rankTier + ")";
        };
    }
}