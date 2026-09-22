package io.github.aindriub.ircweb.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * While no account exists, the setup page is the only page.
 *
 * <p>This is what makes "choose a password" the only way in rather than a step
 * someone is trusted to take later. It runs ahead of authentication, because
 * before setup there is nobody who could authenticate.
 */
public class SetupRequiredFilter extends OncePerRequestFilter {

    /** Reachable during setup: the page itself, its stylesheet, its API, and probes. */
    private static final Set<String> OPEN = Set.of(
            "/setup.html", "/login.css", "/api/setup", "/health", "/error", "/favicon.ico");

    private final AppUserService users;

    public SetupRequiredFilter(AppUserService users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!users.needsSetup()) {
            // Once there is an account the setup page is not just useless but
            // misleading, so it stops existing rather than showing a form that
            // would be refused on submit.
            if ("/setup.html".equals(path)) {
                response.sendRedirect("/login.html");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (OPEN.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/") || path.startsWith("/ws/")) {
            // Whatever is calling is not a browser following redirects, so say what
            // is wrong in a form it can read instead of sending it a login page.
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"this application has not been set up yet\"}");
            return;
        }

        response.sendRedirect("/setup.html");
    }
}
