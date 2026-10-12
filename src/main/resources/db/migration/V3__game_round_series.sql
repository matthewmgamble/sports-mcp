-- Postseason context and unset start times (Sprint 6).
ALTER TABLE games ADD COLUMN round TEXT;            -- "ALCS Game 4", "1st Round"
ALTER TABLE games ADD COLUMN series TEXT;           -- "TOR leads 2-1"
ALTER TABLE games ADD COLUMN start_time_tbd INTEGER NOT NULL DEFAULT 0;
