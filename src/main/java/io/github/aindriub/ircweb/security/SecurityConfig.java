package io.github.aindriub.ircweb.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Form login over a session.
 *
 * <p>This application holds IRC and SASL credentials and will send arbitrary
 * protocol lines on request. Unauthenticated, anyone who could reach the port could
 * do both. That was tolerable while it only ran on loopback and remembered nothing;
 * it stopped being tolerable the moment it started storing passwords.
 */
@Configuration
public class SecurityConfig {

    /**
     * Backed by the app's own {@link DataSource}, reading and writing the
     * {@code persistent_logins} table that Hibernate creates. Table creation is
     * left to Hibernate on purpose: {@link JdbcTokenRepositoryImpl} can create it
     * too, but only once - asked to do that again on a second start, against a
     * table that is already there, it fails.
     */
    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppUserService users,
            SecretCodec secretCodec, PersistentTokenRepository tokenRepository) throws Exception {
        // The token goes in a cookie the browser's JavaScript can read and echo
        // back, which is what lets a fetch() from the same page carry it.
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            // Ahead of authentication on purpose: before setup there is nobody to
            // authenticate as, so this has to be able to answer first.
            .addFilterBefore(new SetupRequiredFilter(users), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // /health is public so a container probe works without credentials;
                // it exposes a count and nothing else.
                .requestMatchers("/login.html", "/login.css", "/api/login", "/health", "/error",
                        "/setup.html", "/api/setup")
                    .permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login.html?failed")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessUrl("/login.html?out")
                // The remember-me logout handler registered by .rememberMe(...)
                // below only cancels the cookie's own series, and only if the
                // request already carries an Authentication - which a remember-me
                // -only reopen (no session) does not have yet, because LogoutFilter
                // runs ahead of RememberMeAuthenticationFilter in the chain and a
                // matched logout URL never reaches it. So this reads the series
                // straight out of the cookie instead, and clears every series for
                // that account, not just the one that reopened it.
                .addLogoutHandler((request, response, authentication) -> {
                    String username = authentication != null ? authentication.getName()
                            : usernameFromRememberMeCookie(request, tokenRepository);
                    if (username != null) {
                        tokenRepository.removeUserTokens(username);
                    }
                })
                .permitAll())
            .rememberMe(remember -> remember
                .key(secretCodec.rememberMeKey())
                .tokenRepository(tokenRepository)
                .tokenValiditySeconds(2592000)
                .rememberMeParameter("remember-me")
                .userDetailsService(users))
            // For the websocket handshake and for anything driving this without a
            // browser. The form login is what people use.
            .httpBasic(basic -> {})
            .csrf(c -> c
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(csrfHandler))
            .exceptionHandling(e -> e
                // The page is a single document that talks over fetch and a socket.
                // Redirecting an API call to a login form gives JavaScript an HTML
                // page where it expected JSON; a 401 is something it can act on. The
                // socket carries the same "signed out" meaning: its own reconnect
                // logic needs a status it can act on, not an HTML login page.
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    request -> request.getRequestURI().startsWith("/api/")
                        || request.getRequestURI().startsWith("/ws/"))
                // Everything else is a browser asking for a page, and a browser
                // should be sent to the login form rather than shown a bare 401.
                // Registered second on purpose: the last one also becomes the
                // fallback, and the fallback should be the friendly one.
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login.html"),
                    request -> true));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Decodes the account a remember-me cookie belongs to without going through
     * {@code RememberMeServices}, which would also rotate the token - not wanted
     * here, where the token is about to be deleted anyway. The cookie's own
     * encoding, per {@code AbstractRememberMeServices}, is
     * {@code base64(urlencode(series) + ":" + urlencode(token))}, with the
     * base64 padding trimmed.
     *
     * <p>The series alone is not proof of anything - it is the public half of the
     * cookie, visible to anyone who can see the Set-Cookie header, e.g. in a
     * shared proxy log. Only the token is secret, so this checks it against the
     * stored row (in constant time, since this is still a credential comparison)
     * before treating the cookie as good for anything. A mismatch, or a cookie
     * that does not decode at all, is answered with "nothing to delete" rather
     * than an error.
     */
    private static String usernameFromRememberMeCookie(HttpServletRequest request,
            PersistentTokenRepository tokenRepository) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (!"remember-me".equals(cookie.getName())) {
                continue;
            }
            try {
                String value = cookie.getValue();
                while (value.length() % 4 != 0) {
                    value = value + "=";
                }
                byte[] bytes = Base64.getDecoder().decode(value);
                String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                if (parts.length < 2) {
                    continue;
                }
                String series = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String presentedToken = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                PersistentRememberMeToken token = tokenRepository.getTokenForSeries(series);
                if (token == null) {
                    continue;
                }
                byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
                byte[] stored = token.getTokenValue().getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(presented, stored)) {
                    return token.getUsername();
                }
            } catch (RuntimeException e) {
                // Not a cookie this could make sense of; nothing to remove for it.
            }
        }
        return null;
    }
}
