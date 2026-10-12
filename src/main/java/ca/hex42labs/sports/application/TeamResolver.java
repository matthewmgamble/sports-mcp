package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.Team;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns what an agent typed ("leafs", "Green Bay", "nhl:tor") into a team, or explains why it
 * can't. Matching is tiered: an exact abbreviation or nickname beats a partial name match, and a
 * tie at the best tier is reported as ambiguous rather than guessed.
 */
public final class TeamResolver {

    /** Maximum candidates returned for an ambiguous query. */
    static final int MAX_CANDIDATES = 10;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final TeamCatalog catalog;

    public TeamResolver(TeamCatalog catalog) {
        this.catalog = catalog;
    }

    public sealed interface Resolution {
        record Match(Team team) implements Resolution {}

        record Ambiguous(String input, List<Team> candidates) implements Resolution {}

        record NotFound(String input) implements Resolution {}
    }

    /**
     * @param input canonical id or free text
     * @param leagueId restrict to one league, or null for all enabled leagues
     */
    public Resolution resolve(String input, String leagueId) {
        if (input == null || input.isBlank()) {
            return new Resolution.NotFound(input == null ? "" : input);
        }
        String trimmed = input.trim();
        if (Ids.isWellFormed(trimmed.toLowerCase(Locale.ROOT))) {
            Optional<Team> exact = catalog.find(trimmed.toLowerCase(Locale.ROOT));
            if (exact.isPresent() && (leagueId == null || exact.get().league().equals(leagueId))) {
                return new Resolution.Match(exact.get());
            }
        }
        List<Scored> scored = score(trimmed, leagueId).toList();
        if (scored.isEmpty()) {
            return new Resolution.NotFound(trimmed);
        }
        int best = scored.get(0).score();
        List<Team> top = scored.stream().filter(s -> s.score() == best).map(Scored::team).toList();
        return top.size() == 1
                ? new Resolution.Match(top.get(0))
                : new Resolution.Ambiguous(trimmed, top.stream().limit(MAX_CANDIDATES).toList());
    }

    /** Ranked matches for a search box ({@code sports_list_teams search=...}). */
    public List<Team> search(String query, String leagueId, int limit) {
        if (query == null || query.isBlank()) {
            return candidates(leagueId)
                    .sorted(Comparator.comparing(Team::name))
                    .limit(limit)
                    .toList();
        }
        return score(query.trim(), leagueId).limit(limit).map(Scored::team).toList();
    }

    private record Scored(Team team, int score) {}

    private Stream<Scored> score(String input, String leagueId) {
        String q = normalize(input);
        if (q.isEmpty()) {
            return Stream.empty();
        }
        return candidates(leagueId)
                .map(t -> new Scored(t, score(t, q)))
                .filter(s -> s.score() > 0)
                .sorted(
                        Comparator.comparingInt(Scored::score)
                                .reversed()
                                .thenComparing(s -> s.team().name()));
    }

    private Stream<Team> candidates(String leagueId) {
        return leagueId == null ? catalog.all().stream() : catalog.byLeague(leagueId).stream();
    }

    static int score(Team t, String q) {
        int best = 0;
        if (q.equals(normalize(t.abbreviation()))) {
            best = Math.max(best, 90);
        }
        for (String alias : t.aliases()) {
            String a = normalize(alias);
            if (q.equals(a)) {
                best = Math.max(best, 85);
            } else if (wordStart(a, q)) {
                best = Math.max(best, 50);
            }
        }
        String name = normalize(t.name());
        String shortName = normalize(t.shortName());
        String location = normalize(t.location());
        if (q.equals(name)) {
            best = Math.max(best, 80);
        }
        // Short name and location share a tier: college short names are place names ("Green
        // Bay" is both the Packers' location and UW-Green Bay's short name), so neither wins.
        if (q.equals(shortName) || q.equals(location)) {
            best = Math.max(best, 70);
        }
        if (wordStart(name, q) || wordStart(shortName, q) || wordStart(location, q)) {
            best = Math.max(best, 50);
        }
        if (q.length() >= 3 && (name.contains(q) || shortName.contains(q))) {
            best = Math.max(best, 30);
        }
        return best;
    }

    /** {@code q} occurs in {@code field} starting at a word boundary ("bay" in "green bay"). */
    private static boolean wordStart(String field, String q) {
        return !field.isEmpty() && (" " + field).contains(" " + q);
    }

    /** Lowercase, accents stripped ("Atlético" matches "atletico"), punctuation to spaces. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String folded =
                DIACRITICS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM
                .matcher(folded.toLowerCase(Locale.ROOT).replace("&", " and "))
                .replaceAll(" ")
                .trim();
    }
}
