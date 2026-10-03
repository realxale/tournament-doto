-- ============================================================
-- V3: клоз как серия матчей
--
-- Клоз больше не «запись игроков»: в нём создаются отдельные
-- матчи, у каждого свой состав (players_match) и свои времена.
-- close.number / match.number — человекочитаемые номера.
-- ============================================================

-- ---------- CLOSE: параметры и привязка к Discord ----------
ALTER TABLE close ADD COLUMN discord_category_id TEXT UNIQUE;
ALTER TABLE close ADD COLUMN immortal_draft BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE close ADD COLUMN gamemode         TEXT NOT NULL DEFAULT 'ap';
ALTER TABLE close ADD COLUMN toxic            BOOLEAN NOT NULL DEFAULT FALSE;

-- номер клоза выдаёт последовательность, чтобы не было гонок
CREATE SEQUENCE close_number_seq;
ALTER TABLE close ALTER COLUMN number SET DEFAULT nextval('close_number_seq');
ALTER TABLE close ALTER COLUMN time_start SET DEFAULT now();

-- ---------- MATCH: свои параметры, номер и жизненный цикл ----------
ALTER TABLE match ADD COLUMN number         INT NOT NULL DEFAULT 1;
ALTER TABLE match ADD COLUMN status         TEXT NOT NULL DEFAULT 'COLLECTING'
    CHECK (status IN ('COLLECTING','FINISHED','CANCELLED'));
ALTER TABLE match ADD COLUMN gamemode         TEXT NOT NULL DEFAULT 'ap';
ALTER TABLE match ADD COLUMN immortal_draft   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE match ADD COLUMN toxic            BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE match ALTER COLUMN time_start SET DEFAULT now();

-- один матч — один номер внутри клоза
CREATE UNIQUE INDEX idx_match_close_number ON match(close_id, number);
CREATE INDEX idx_match_status ON match(status);