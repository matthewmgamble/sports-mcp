package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.persistence.GameLinkRepository;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.provider.GameLinker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GameLinker} over {@code game_links}. The same game is recognised across providers by
 * matchup (home and away team) and US-Eastern day; doubleheaders are paired in start order.
 */
public final class GameLinks implements GameLinker {

    private static final Logger log = LoggerFactory.getLogger(GameLinks.class);

    private static final Comparator<Game> START_ORDER =
            Comparator.comparing(Game::startsAt).thenComparing(Game::providerEventId);

    private final GameRepository games;
    private final GameLinkRepository links;
    private final Clock clock;

    public GameLinks(GameRepository games, GameLinkRepository links, Clock clock) {
        this.games = games;
        this.links = links;
        this.clock = clock;
    }

    /** {@code mlb:espn401908026}: a fallback game the primary provider hasn't seen yet. */
    public static String provisionalId(League league, Game fallbackGame) {
        return Ids.game(league.id(), fallbackGame.provider() + fallbackGame.providerEventId());
    }

    @Override
    public List<Game> link(League league, String primaryProvider, List<Game> fallbackGames) {
        List<Game> out = new ArrayList<>(fallbackGames.size());
        if (fallbackGames.isEmpty()) {
            return out;
        }
        String fallbackProvider = fallbackGames.getFirst().provider();
        Map<String, String> known =
                links.gameIdsFor(
                        fallbackProvider,
                        fallbackGames.stream().map(Game::providerEventId).toList());
        for (Game g : fallbackGames) {
            String canonical = known.get(g.providerEventId());
            if (canonical == null) {
                Optional<Game> match =
                        pair(
                                g,
                                fallbackGames,
                                sameMatchupAndDay(league, primaryProvider, g),
                                c -> !links.isLinked(fallbackProvider, c.id()));
                if (match.isPresent()
                        && links.link(
                                league.id(),
                                fallbackProvider,
                                g.providerEventId(),
                                match.get().id(),
                                clock.instant())) {
                    canonical = match.get().id();
                    log.debug(
                            "Linked {} {} to {}", fallbackProvider, g.providerEventId(), canonical);
                }
            }
            out.add(
                    canonical != null
                            ? withIdentity(
                                    g,
                                    canonical,
                                    primaryProvider,
                                    canonical.substring(canonical.indexOf(':') + 1))
                            : withIdentity(
                                    g,
                                    provisionalId(league, g),
                                    g.provider(),
                                    g.providerEventId()));
        }
        return out;
    }

    @Override
    public void adopt(League league, String fallbackProvider, List<Game> primaryGames) {
        if (primaryGames.isEmpty() || !games.hasProvider(league.id(), fallbackProvider)) {
            return;
        }
        for (Game p : primaryGames) {
            List<Game> provisional = sameMatchupAndDay(league, fallbackProvider, p);
            if (provisional.isEmpty()) {
                continue;
            }
            pair(p, primaryGames, provisional, c -> true)
                    .ifPresent(
                            c -> {
                                links.link(
                                        league.id(),
                                        fallbackProvider,
                                        c.providerEventId(),
                                        p.id(),
                                        clock.instant());
                                games.deleteById(c.id());
                                log.info("{} replaces provisional {}", p.id(), c.id());
                            });
        }
    }

    private List<Game> sameMatchupAndDay(League league, String provider, Game g) {
        LocalDate day = ScoreRefreshCoordinator.bucketOf(g);
        Instant from = day.atStartOfDay(ScoreRefreshCoordinator.BUCKET_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ScoreRefreshCoordinator.BUCKET_ZONE).toInstant();
        return games.findMatchup(league.id(), provider, g.homeTeamId(), g.awayTeamId(), from, to);
    }

    /**
     * Picks {@code g}'s counterpart among {@code candidates} (the other provider's games for the
     * same matchup and day). When both sides list the same number of games they're paired in start
     * order: for a doubleheader statsapi often gives game 2 a nominal time while ESPN gives the
     * real one (BAL@NYY 2026-09-25: 20:05/20:10 vs 20:05/23:30), so nearest-time matching could
     * cross them. Otherwise, the nearest start that's still available.
     *
     * @param batch the games {@code g} arrived with (to find its siblings in the same matchup)
     */
    static Optional<Game> pair(
            Game g, List<Game> batch, List<Game> candidates, Predicate<Game> available) {
        LocalDate day = ScoreRefreshCoordinator.bucketOf(g);
        List<Game> siblings =
                batch.stream()
                        .filter(
                                b ->
                                        b.homeTeamId().equals(g.homeTeamId())
                                                && b.awayTeamId().equals(g.awayTeamId())
                                                && ScoreRefreshCoordinator.bucketOf(b).equals(day))
                        .sorted(START_ORDER)
                        .toList();
        List<Game> options = candidates.stream().sorted(START_ORDER).toList();
        int rank = siblings.indexOf(g);
        if (rank >= 0 && siblings.size() == options.size()) {
            Game c = options.get(rank);
            return available.test(c) ? Optional.of(c) : Optional.empty();
        }
        return options.stream()
                .filter(available)
                .min(Comparator.comparing(c -> Duration.between(c.startsAt(), g.startsAt()).abs()));
    }

    private static Game withIdentity(Game g, String id, String provider, String providerEventId) {
        return new Game(
                id,
                g.league(),
                provider,
                providerEventId,
                g.season(),
                g.seasonType(),
                g.homeTeamId(),
                g.awayTeamId(),
                g.startsAt(),
                g.status(),
                g.statusDetail(),
                g.period(),
                g.periodType(),
                g.clock(),
                g.homeScore(),
                g.awayScore(),
                g.homeShootout(),
                g.awayShootout(),
                g.venue(),
                g.broadcasts(),
                g.situation(),
                g.fetchedAt(),
                g.providerUpdatedAt(),
                g.round(),
                g.series(),
                g.startTimeTbd());
    }
}
