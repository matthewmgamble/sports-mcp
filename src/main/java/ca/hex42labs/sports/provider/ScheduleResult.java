package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.Team;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Games for a date window, possibly assembled from several upstream calls. Individual calls can
 * fail without losing the rest; {@link #failures()} says which, and {@link #failedDays()} lists the
 * days whose single-day request failed (so a fallback source can fill exactly those).
 */
public record ScheduleResult(
        List<Game> games,
        List<Team> teams,
        Instant fetchedAt,
        List<String> failures,
        List<LocalDate> failedDays) {

    public ScheduleResult {
        games = List.copyOf(games);
        teams = List.copyOf(teams);
        failures = List.copyOf(failures);
        failedDays = List.copyOf(failedDays);
    }

    public ScheduleResult(
            List<Game> games, List<Team> teams, Instant fetchedAt, List<String> failures) {
        this(games, teams, fetchedAt, failures, List.of());
    }

    public boolean complete() {
        return failures.isEmpty();
    }

    /** Accumulates batches, de-duplicating games and teams by id. */
    public static final class Builder {
        private final Map<String, Game> games = new LinkedHashMap<>();
        private final Map<String, Team> teams = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private final List<LocalDate> failedDays = new ArrayList<>();
        private Instant fetchedAt;
        private ProviderException lastError;

        public Builder add(List<Game> batchGames, List<Team> batchTeams, Instant batchFetchedAt) {
            batchGames.forEach(g -> games.merge(g.id(), g, Builder::preferMakeup));
            batchTeams.forEach(t -> teams.putIfAbsent(t.id(), t));
            if (fetchedAt == null || batchFetchedAt.isBefore(fetchedAt)) {
                fetchedAt = batchFetchedAt; // report the oldest data in the result
            }
            return this;
        }

        public Builder add(ProviderBatch batch) {
            return add(batch.games(), batch.teams(), batch.fetchedAt());
        }

        public Builder fail(String what, ProviderException e) {
            failures.add(what + ": " + e.getMessage());
            lastError = e;
            return this;
        }

        /** A single-day request failed. */
        public Builder failDay(LocalDate day, ProviderException e) {
            failedDays.add(day);
            return fail(day.toString(), e);
        }

        /**
         * @param now fetch time to report when nothing succeeded
         * @throws ProviderException if every call failed (there's nothing to save)
         */
        public ScheduleResult build(Instant now) throws ProviderException {
            if (fetchedAt == null && lastError != null) {
                throw lastError;
            }
            return new ScheduleResult(
                    List.copyOf(games.values()),
                    List.copyOf(teams.values()),
                    fetchedAt == null ? now : fetchedAt,
                    failures,
                    failedDays);
        }

        /**
         * A postponed game is listed twice with the same id: on its original date as POSTPONED and
         * on the makeup date with its real state (MLB gamePk 824785, 2026-09-22/23). Keep the
         * makeup listing.
         */
        static Game preferMakeup(Game a, Game b) {
            boolean aPostponed = a.status() == GameStatus.POSTPONED;
            boolean bPostponed = b.status() == GameStatus.POSTPONED;
            if (aPostponed != bPostponed) {
                return aPostponed ? b : a;
            }
            return b.startsAt().isAfter(a.startsAt()) ? b : a;
        }
    }
}
