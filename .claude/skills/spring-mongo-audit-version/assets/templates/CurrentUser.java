// TEMPLATE — copy into your project and adjust the package.
// This is a simple request-scoped "current user" holder for demos/services without Spring Security.
// If you HAVE Spring Security, delete this and source the user from the SecurityContext in both your
// AuditorAware and the AuditFieldsAspect instead.
package com.yourorg.config;

/**
 * Thread-local holder for the "current user", consumed by the {@code AuditorAware} (save path) and the
 * {@code AuditFieldsAspect} (template path) so both stamp {@code createdBy}/{@code updatedBy} from the
 * same source. Populate it per request (see {@code CurrentUserFilter}) or per job.
 */
public final class CurrentUser {

    public static final String DEFAULT = "system";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(String user) {
        HOLDER.set(user);
    }

    public static String get() {
        String user = HOLDER.get();
        return (user == null || user.isBlank()) ? DEFAULT : user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
