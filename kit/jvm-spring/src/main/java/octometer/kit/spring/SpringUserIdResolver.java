package octometer.kit.spring;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the user id of one request (design decision D18, contract rule
 * C6). The app gives one implementation, as a Spring bean, to
 * {@link IngestController}. A lambda that reads the session of
 * {@code request} is one example.
 *
 * <p>{@link #resolve(HttpServletRequest)} runs on the thread of the
 * request, before the store call. Keep it short.
 */
@FunctionalInterface
public interface SpringUserIdResolver {

    /**
     * Returns the internal user id of the app for {@code request}, or
     * {@code null} when no user is signed in (contract rule C6). The
     * value must not be a username, an email address, or an IP address.
     */
    String resolve(HttpServletRequest request);
}
