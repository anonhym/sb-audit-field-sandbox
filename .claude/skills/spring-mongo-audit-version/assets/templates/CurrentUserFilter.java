// TEMPLATE — copy into your project and adjust the package.
// Demo plumbing: populates CurrentUser from an X-User header. Replace with your real auth in prod
// (e.g. read the principal from the SecurityContext). Delete if you use Spring Security directly.
package com.yourorg.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link CurrentUser} from the {@code X-User} request header for the duration of each request
 * (defaulting to {@code system} when absent), then clears it. Lets you drive {@code createdBy} /
 * {@code updatedBy} per call, e.g. {@code curl -H 'X-User: alice' ...}.
 */
@Component
public class CurrentUserFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            CurrentUser.set(request.getHeader("X-User"));
            filterChain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }
}
