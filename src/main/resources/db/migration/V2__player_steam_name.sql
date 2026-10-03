-- ============================================================
-- V2: привязка имени Steam-аккаунта (/bind)
-- Модалка /bind собирает имя Steam-аккаунта (например "Suma1L"),
-- а не SteamID64, поэтому храним его отдельной колонкой.
-- players.steam_id (BIGINT) остаётся под SteamID64 и заполняется позже.
-- ============================================================

ALTER TABLE players ADD COLUMN steam_name TEXT;

-- поиск игрока по имени Steam-аккаунта (регистронезависимо)
CREATE INDEX idx_players_steam_name ON players (lower(steam_name));
