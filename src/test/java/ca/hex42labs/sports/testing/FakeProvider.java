package ca.hex42labs.sports.testing;

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

    /** Every fetchSchedule window requested, as "league from..to". */
    public final List<String> scheduleCalls = new CopyOnWriteArrayList<>();

    public FakeProvider(String id) {
        this.id = id;
    }

    public FakeProvider teams(String league, List<Team> value) {
        teams.put(league, value);
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
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) {
        throw new UnsupportedOperationException();
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
