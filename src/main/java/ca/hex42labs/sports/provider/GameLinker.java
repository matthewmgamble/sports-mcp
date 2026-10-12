package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.League;
import java.util.List;

/**
 * Keeps one identity per game when a league's games can come from two providers (MLB: statsapi
 * primary, ESPN fallback). The primary provider's ids are canonical.
 */
public interface GameLinker {

    /**
     * Rewrites fallback-provider games onto canonical ids: an existing link, else a match against
     * the primary provider's stored games (recording the link), else a provisional id.
     */
    List<Game> link(League league, String primaryProvider, List<Game> fallbackGames);

    /**
     * The primary provider now has these games: any provisional fallback rows for them are linked
     * and removed, so the primary rows replace them.
     */
    void adopt(League league, String fallbackProvider, List<Game> primaryGames);
}
