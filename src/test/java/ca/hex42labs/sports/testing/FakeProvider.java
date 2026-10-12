package ca.hex42labs.sports.testing;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.SportsProvider;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable in-memory provider. */
public final class FakeProvider implements SportsProvider {

    private final String id;
    private final Map<String, List<Team>> teams = new HashMap<>();
    private volatile ProviderException failWith;
    public final AtomicInteger teamCalls = new AtomicInteger();

    public FakeProvider(String id) {
        this.id = id;
    }

    public FakeProvider teams(String league, List<Team> value) {
        teams.put(league, value);
        return this;
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
}
