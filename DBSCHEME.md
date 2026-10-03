players:
  id (PK)
  dota_id
  discord_id
  steam_name  (/bind — имя Steam-аккаунта, до получения SteamID64)
  dota_rank
  win_points
---
close:            -- КЛОЗ = серия матчей
  id (PK)
  number            (уникальный, выдаёт close_number_seq)
  owner_id -> players.id
  discord_category_id (категория Discord, уникальна)
  status            OPEN | IN_PROGRESS | FINISHED | CANCELLED
  time_start
  time_end
  immortal_draft    параметр из модалки
  gamemode          параметр из модалки
  toxic             параметр из модалки
---
match:             -- МАТЧ = одна игра со своим составом
  id (PK)
  close_id -> close.id
  number            номер матча внутри клоза, уникален в паре с close_id
  status            COLLECTING | FINISHED | CANCELLED
  result: a or b (winner), NULL пока неизвестен
  time_start - time_end
  immortal_draft / gamemode / toxic   (наследуются от клоза)
-----
bet-logs:
  match
  player_id
  sum
  odds
  time
  id
  payout
  ------
players_match :     -- состав конкретного матча
  match_id -> match.id
  player_id -> players.id
  team a or b        (1..5 по порядку записи — a, 6..10 — b)
  -------
