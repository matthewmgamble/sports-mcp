-- Cross-provider game identity. A league's primary provider owns canonical game ids (MLB:
-- statsapi gamePk). When a fallback provider (ESPN) supplies a game, its event id is linked to the
-- canonical game here, so the same game never appears twice and ids stay stable.
-- A fallback game with no primary match yet is stored under a provisional id
-- ({league}:{provider}{eventId}, e.g. mlb:espn401908026) until the primary provider sees it.

CREATE TABLE game_links (
    league            TEXT NOT NULL,
    provider          TEXT NOT NULL,             -- the fallback provider, e.g. espn
    provider_event_id TEXT NOT NULL,             -- its event id, e.g. 401908026
    game_id           TEXT NOT NULL,             -- canonical game, e.g. mlb:849812
    linked_at         TEXT NOT NULL,
    PRIMARY KEY (provider, provider_event_id),
    UNIQUE (provider, game_id)
);
