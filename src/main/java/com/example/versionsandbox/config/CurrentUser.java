package com.example.versionsandbox.config;

/**
 * Thread-local holder for the "current user", consumed by {@link AuditingConfig}'s
 * {@code AuditorAware} to populate {@code @CreatedBy} / {@code @LastModifiedBy}. In a real service
 * this would come from the security context; here it is set per request from the {@code X-User}
 * header (see {@code CurrentUserFilter}) so the harness can vary the auditor.
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
