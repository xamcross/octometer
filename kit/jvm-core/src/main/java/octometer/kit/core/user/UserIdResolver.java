package octometer.kit.core.user;

/**
 * Resolves the user id of the current request (design decision D18). The
 * app gives the implementation, for example a lookup in the session.
 */
public interface UserIdResolver {

    /**
     * Returns the internal user id of the app, or {@code null} when no
     * user is signed in (contract rule C6). The value must not be a
     * username, an email address, or an IP address.
     */
    String resolve();
}
