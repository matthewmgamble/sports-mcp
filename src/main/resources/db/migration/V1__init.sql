-- Core schema. Timestamps are ISO-8601 UTC strings (e.g. 2026-10-13T00:15:00Z) so they sort
-- lexically and stay readable in the sqlite3 shell.

CREATE TABLE teams (
    id                TEXT PRIMARY KEY,          -- canonical, e.g. nhl:tor, ncaaf:145
    league            TEXT NOT NULL,
    provider          TEXT NOT NULL,             -- espn | nhl | mlb
    provider_team_id  TEXT NOT NULL,
    name              TEXT NOT NULL,             -- "Toronto Maple Leafs"
    short_name        TEXT,                      -- "Maple Leafs"
    abbreviation      TEXT,                      -- "TOR"
    location          TEXT,                      -- "Toronto"
    logo_url          TEXT,
    aliases_json      TEXT,                      -- ["leafs"]
    updated_at        TEXT NOT NULL,
    UNIQUE (league, provider_team_id)
);

CREATE TABLE games (
    id                  TEXT PRIMARY KEY,        -- canonical, e.g. nfl:401872994
    league              TEXT NOT NULL,
    provider            TEXT NOT NULL,
    provider_event_id   TEXT NOT NULL,
    season              TEXT,
    season_type         TEXT,
    home_team_id        TEXT NOT NULL,
    away_team_id        TEXT NOT NULL,
    starts_at           TEXT NOT NULL,
    status              TEXT NOT NULL,           -- SCHEDULED | LIVE | FINAL | POSTPONED | SUSPENDED | CANCELLED | UNKNOWN
    status_detail       TEXT,
    period              INTEGER,
    period_type         TEXT,
    clock               TEXT,
    home_score          INTEGER,
    away_score          INTEGER,
    home_shootout       INTEGER,
    away_shootout       INTEGER,
    venue               TEXT,
    broadcasts_json     TEXT,
    situation_json      TEXT,
    fetched_at          TEXT NOT NULL,
    provider_updated_at TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_games_league_start ON games (league, starts_at);
CREATE INDEX idx_games_home_start   ON games (home_team_id, starts_at);
CREATE INDEX idx_games_away_start   ON games (away_team_id, starts_at);
CREATE INDEX idx_games_status_start ON games (status, starts_at);

CREATE TABLE sync_state (
    league          TEXT NOT NULL,
    kind            TEXT NOT NULL,               -- TEAMS | SCHEDULE
    window_start    TEXT,
    window_end      TEXT,
    last_success_at TEXT,
    last_attempt_at TEXT,
    error           TEXT,
    PRIMARY KEY (league, kind)
);

-- Optional troubleshooting capture (off by default, 24h retention).
CREATE TABLE raw_payloads (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    provider    TEXT NOT NULL,
    url_hash    TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    body_gz     BLOB NOT NULL
);

CREATE INDEX idx_raw_payloads_captured ON raw_payloads (captured_at);
