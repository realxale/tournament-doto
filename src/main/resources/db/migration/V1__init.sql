-- ============================================================
-- V1: Initial schema
-- Игроки, роли, клозы, матчи, ставки, валюта
-- ============================================================

-- ---------- PLAYERS ----------
CREATE TABLE players (
    id              BIGSERIAL PRIMARY KEY,
    discord_id      BIGINT UNIQUE NOT NULL,
    steam_id        BIGINT UNIQUE,
    dota_account_id BIGINT UNIQUE,
    name            TEXT NOT NULL,
    mmr             INT,
    rank_tier       INT,
    win_points      BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Роли игрока (1..5), с приоритетом
CREATE TABLE player_roles (
    player_id   BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    role        SMALLINT NOT NULL CHECK (role BETWEEN 1 AND 5),
    priority    SMALLINT NOT NULL DEFAULT 1,
    PRIMARY KEY (player_id, role)
);

-- ---------- CLOSE (lobby) ----------
CREATE TABLE close (
    id          BIGSERIAL PRIMARY KEY,
    number      INT UNIQUE NOT NULL,
    owner_id    BIGINT NOT NULL REFERENCES players(id),
    status      TEXT NOT NULL DEFAULT 'OPEN'
                CHECK (status IN ('OPEN','IN_PROGRESS','FINISHED','CANCELLED')),
    time_start  TIMESTAMPTZ NOT NULL,
    time_end    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Кто зарегистрирован в клозе (основа + запасные)
CREATE TABLE close_participant (
    close_id    BIGINT NOT NULL REFERENCES close(id) ON DELETE CASCADE,
    player_id   BIGINT NOT NULL REFERENCES players(id),
    is_reserve  BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (close_id, player_id)
);

-- ---------- MATCH ----------
CREATE TABLE match (
    id             BIGSERIAL PRIMARY KEY,
    close_id       BIGINT NOT NULL REFERENCES close(id) ON DELETE CASCADE,
    dota_match_id  BIGINT UNIQUE,
    result         CHAR(1) CHECK (result IN ('a','b')),
    time_start     TIMESTAMPTZ,
    time_end       TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Кто играл в матче (одна строка на игрока!)
CREATE TABLE players_match (
    match_id   BIGINT NOT NULL REFERENCES match(id) ON DELETE CASCADE,
    player_id  BIGINT NOT NULL REFERENCES players(id),
    team       CHAR(1) NOT NULL CHECK (team IN ('a','b')),
    PRIMARY KEY (match_id, player_id)
);

-- ---------- BETS ----------
CREATE TABLE bet_logs (
    id              BIGSERIAL PRIMARY KEY,
    match_id        BIGINT NOT NULL REFERENCES match(id) ON DELETE CASCADE,
    player_id       BIGINT NOT NULL REFERENCES players(id),
    sum             BIGINT NOT NULL CHECK (sum > 0),
    odds            NUMERIC(8,4) NOT NULL,
    predicted_side  CHAR(1) NOT NULL CHECK (predicted_side IN ('a','b')),
    payout          BIGINT NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'PLACED'
                    CHECK (status IN ('PLACED','WON','LOST','REFUNDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- POINTS LEDGER ----------
-- Истина о балансе. players.win_points — кэш.
CREATE TABLE points_tx (
    id          BIGSERIAL PRIMARY KEY,
    player_id   BIGINT NOT NULL REFERENCES players(id),
    amount      BIGINT NOT NULL,
    reason      TEXT NOT NULL,
    ref_id      BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- INDEXES ----------
CREATE INDEX idx_match_close        ON match(close_id);
CREATE INDEX idx_match_player_pid   ON players_match(player_id);
CREATE INDEX idx_bet_match          ON bet_logs(match_id);
CREATE INDEX idx_bet_player         ON bet_logs(player_id);
CREATE INDEX idx_points_player      ON points_tx(player_id, created_at);
CREATE INDEX idx_close_time         ON close(time_start);
CREATE INDEX idx_close_participant  ON close_participant(player_id);
