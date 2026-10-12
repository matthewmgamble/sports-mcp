package ca.hex42labs.sports.testing;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.SportsProvider;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable in-memory provider. */
public class FakeProvider implements SportsProvider {

    private final String id;
    private final Map<String, List<Team>> teams = new HashMap<>();
    private volatile ProviderException failWith;
    private final Map<String, ScheduleResult> schedules = new HashMap<>();
    private volatile ProviderException scheduleFailure;
    public final AtomicInteger teamCalls = new AtomicInteger();

    /** Scripted scoreboard: (league, bucket) to batch, or throw. */
    public interface ScoreboardFn {
        ProviderBatch apply(League league, LocalDate bucket) throws ProviderException;
    }

    public interface RefreshGameFn {
        Optional<Game> apply(Game game) throws ProviderException;
    }

    private volatile ScoreboardFn scoreboard;
    private volatile RefreshGameFn refreshGame;

    /** Every fetchScoreboard call as "league@date". */
    public final List<String> scoreboardCalls = new CopyOnWriteArrayList<>();

    /** Every refreshGame call, by game id. */
    public final List<String> refreshGameCalls = new CopyOnWriteArrayList<>();

    /** Every fetchSchedule window requested, as "league from..to". */
    public final List<String> scheduleCalls = new CopyOnWriteArrayList<>();

    public FakeProvider(String id) {
        this.id = id;
    }

    public FakeProvider teams(String league, List<Team> value) {
        teams.put(league, value);
        return this;
    }

    public FakeProvider scoreboard(ScoreboardFn fn) {
        this.scoreboard = fn;
        return this;
    }

    public FakeProvider refreshGame(RefreshGameFn fn) {
        this.refreshGame = fn;
        return this;
    }

    public FakeProvider schedule(String league, ScheduleResult result) {
        schedules.put(league, result);
        return this;
    }

    public void failSchedulesWith(ProviderException e) {
        this.scheduleFailure = e;
    }

    public void failWith(ProviderException e) {
        this.failWith = e;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<Team> fetchTeams(League league) throws ProviderException {
        teamCalls.incrementAndGet();
        if (failWith != null) {
            throw failWith;
        }
        return teams.getOrDefault(league.id(), List.of());
    }

    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        scoreboardCalls.add(league.id() + "@" + bucket);
        if (scoreboard == null) {
            throw new UnsupportedOperationException("no scoreboard scripted");
        }
        return scoreboard.apply(league, bucket);
    }

    private final Map<String, ScheduleResult> teamSchedules = new HashMap<>();

    /** Every fetchTeamSchedule call, by team id. */
    public final List<String> teamScheduleCalls = new CopyOnWriteArrayList<>();

    public FakeProvider teamSchedule(String teamId, ScheduleResult result) {
        teamSchedules.put(teamId, result);
        return this;
    }

    @Override
    public ScheduleResult fetchTeamSchedule(League league, Team team) throws ProviderException {
        teamScheduleCalls.add(team.id());
        if (scheduleFailure != null) {
            throw scheduleFailure;
        }
        ScheduleResult r = teamSchedules.get(team.id());
        return r != null
                ? r
                : new ScheduleResult(List.of(), List.of(), java.time.Instant.EPOCH, List.of());
    }

    @Override
    public boolean hasPerGameFeed() {
        return refreshGame != null;
    }

    @Override
    public Optional<Game> refreshGame(League league, Game game) throws ProviderException {
        refreshGameCalls.add(game.id());
        return refreshGame == null ? Optional.empty() : refreshGame.apply(game);
    }

    @Override
    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException {
        scheduleCalls.add(league.id() + " " + from + ".." + to);
        if (scheduleFailure != null) {
            throw scheduleFailure;
        }
        ScheduleResult r = schedules.get(league.id());
        return r != null
                ? r
                : new ScheduleResult(List.of(), List.of(), java.time.Instant.EPOCH, List.of());
    }
}
