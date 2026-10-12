package ca.hex42labs.sports.api.http;

import java.util.Optional;

/**
 * The caller of the request being handled on this thread. Handlers run on the request thread (Jetty
 * virtual threads; MCP with immediate execution), so a thread-local is enough.
 */
public final class RequestContext {

    private static final ThreadLocal<ClientIdentity.Client> CURRENT = new ThreadLocal<>();

    private RequestContext() {}

    static void set(ClientIdentity.Client client) {
        CURRENT.set(client);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Optional<ClientIdentity.Client> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
