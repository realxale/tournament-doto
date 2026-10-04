-- ===========================================================
-- V5: Таблица обвиняемых — бан игрока в клозах
--
-- Отдельная миграция, а не правка V1: у баз, где V1 уже применён,
-- Flyway сверяет checksum и отказался бы стартовать.
--
-- Список плоский: кто обвинён, за что и до какого момента. Бан
-- глобальный — игрок не запишется ни в один клоз, пока срок не истёк.
-- ===========================================================

CREATE TABLE close_ban (
    id          BIGSERIAL PRIMARY KEY,
    -- Discord id напрямую, а не через players: обвиняемый мог ни разу
    -- не выполнить /bind, и бан всё равно нужно уметь выдать
    discord_id  BIGINT NOT NULL,
    ban_reason  VARCHAR(1000) NOT NULL DEFAULT 'без причины',
    -- на сколько часов выдали. 0 = навсегда
    duration_hours INT NOT NULL DEFAULT 0 CHECK (duration_hours >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL = бессрочный бан. Именно NULL, а не 0: истёкший срок и
    -- бессрочность — разные вещи
    time_end    TIMESTAMPTZ,
    -- снят ли досрочно через /close_unban. Строка остаётся навсегда:
    -- это лог и источник правды, а не текущее состояние
    lifted      BOOLEAN NOT NULL DEFAULT FALSE,
    lifted_by   BIGINT,
    lifted_at   TIMESTAMPTZ
);

-- Поиск бана и истории идёт по игроку, поэтому индекс обязателен:
-- проверка бана происходит на каждом клике «Записаться»
CREATE INDEX idx_close_ban_discord_id ON close_ban(discord_id);
