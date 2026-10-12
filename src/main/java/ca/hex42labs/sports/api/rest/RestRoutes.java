package ca.hex42labs.sports.api.rest;

import ca.hex42labs.sports.api.ApiException;
import ca.hex42labs.sports.api.Schemas;
import ca.hex42labs.sports.api.SportsService;
import ca.hex42labs.sports.api.dto.ErrorBody;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned read-only REST API. Every endpoint returns the same envelope as the matching MCP tool.
 * Query parameter names match the tool arguments ({@code tz} is accepted as an alias for {@code
 * timezone}).
 */
public final class RestRoutes {

    private static final Logger log = LoggerFactory.getLogger(RestRoutes.class);
    public static final String PREFIX = "/api/v1";
    private static final String API_ERROR = "sports.apiError";

    private RestRoutes() {}

    public static void mount(RoutesConfig routes, SportsService s, Map<String, Object> openApi) {
        routes.before(
                PREFIX + "/*",
                ctx -> {
                    // Public, read-only data: any origin may read it.
                    ctx.header("Access-Control-Allow-Origin", "*");
                    ctx.header("Cache-Control", "public, max-age=15");
                });
        routes.get(
                PREFIX + "/leagues",
                ctx -> {
                    ctx.header("Cache-Control", "public, max-age=3600");
                    ctx.json(s.leagues());
                });
        routes.get(
                PREFIX + "/teams",
                ctx ->
                        ctx.json(
                                s.teams(
                                        q(ctx, "league"),
                                        q(ctx, "search"),
                                        integer(ctx, "limit"))));
        routes.get(
                PREFIX + "/leagues/{league}/teams",
                ctx -> {
                    ctx.header("Cache-Control", "public, max-age=3600");
                    ctx.json(
                            s.teams(
                                    ctx.pathParam("league"),
                                    q(ctx, "search"),
                                    integer(ctx, "limit")));
                });
        routes.get(
                PREFIX + "/games",
                ctx ->
                        ctx.json(
                                s.games(
                                        q(ctx, "league"),
                                        q(ctx, "team"),
                                        q(ctx, "date"),
                                        q(ctx, "from"),
                                        q(ctx, "to"),
                                        q(ctx, "status"),
                                        tz(ctx),
                                        integer(ctx, "limit"))));
        routes.get(
                PREFIX + "/games/live",
                ctx -> {
                    ctx.header("Cache-Control", "public, max-age=5");
                    ctx.json(
                            s.live(
                                    q(ctx, "league"),
                                    q(ctx, "team"),
                                    bool(ctx, "refresh"),
                                    tz(ctx)));
                });
        routes.get(
                PREFIX + "/games/{gameId}",
                ctx -> ctx.json(s.game(ctx.pathParam("gameId"), bool(ctx, "refresh"), tz(ctx))));
        routes.get(
                PREFIX + "/teams/{team}/schedule",
                ctx ->
                        ctx.json(
                                s.teamSchedule(
                                        ctx.pathParam("team"),
                                        q(ctx, "league"),
                                        q(ctx, "from"),
                                        q(ctx, "to"),
                                        integer(ctx, "limit"),
                                        tz(ctx))));
        routes.get(
                "/openapi.json",
                ctx -> {
                    ctx.header("Access-Control-Allow-Origin", "*");
                    ctx.json(openApi);
                });

        routes.exception(
                ApiException.class,
                (e, ctx) -> {
                    ctx.attribute(
                            API_ERROR, true); // keep the 404 handler below from replacing this body
                    ctx.status(e.status()).json(e.body());
                });
        routes.error(
                404,
                ctx -> {
                    if (ctx.path().startsWith(PREFIX) && ctx.attribute(API_ERROR) == null) {
                        ctx.json(
                                new ErrorBody(
                                        new ErrorBody.Detail(
                                                "not_found",
                                                "No such endpoint. See /openapi.json.",
                                                Map.of())));
                    }
                });
        routes.exception(
                Exception.class,
                (e, ctx) -> {
                    log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
                    ctx.status(500)
                            .json(
                                    new ErrorBody(
                                            new ErrorBody.Detail(
                                                    "internal",
                                                    "Something went wrong on our side.",
                                                    Map.of())));
                });
    }

    /** OpenAPI document: hand-written paths plus components generated from schemas.json. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> openApi(Schemas schemas, String version) {
        Map<String, Object> doc = new LinkedHashMap<>(Schemas.load("/api/openapi.json"));
        Map<String, Object> info = new LinkedHashMap<>((Map<String, Object>) doc.get("info"));
        info.put("version", version);
        doc.put("info", info);
        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> all = new LinkedHashMap<>(schemas.openApiComponents());
        all.put("LeagueList", schemas.envelopeRef("list:League"));
        all.put("TeamList", schemas.envelopeRef("list:Team"));
        all.put("GameList", schemas.envelopeRef("list:Game"));
        all.put("GameOne", schemas.envelopeRef("one:Game"));
        components.put("schemas", all);
        components.put("responses", doc.getOrDefault("x-responses", Map.of()));
        doc.remove("x-responses");
        doc.put("components", components);
        return doc;
    }

    private static String q(Context ctx, String name) {
        return ctx.queryParam(name);
    }

    private static String tz(Context ctx) {
        String tz = ctx.queryParam("timezone");
        return tz != null ? tz : ctx.queryParam("tz");
    }

    private static Integer integer(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw ApiException.invalid(name + " must be a whole number.");
        }
    }

    private static Boolean bool(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null ? null : Boolean.valueOf(v.trim());
    }
}
