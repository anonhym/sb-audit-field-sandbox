package com.example.versionsandbox.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

/**
 * Populates {@link CurrentUser} from the {@code X-User} request header for the duration of each
 * request (defaulting to {@code system} when absent), then clears it. Lets the harness drive
 * {@code createdBy} / {@code updatedBy} per call, e.g. {@code curl -H 'X-User: alice' ...}.
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
