package io.github.aindriub.ircweb.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;

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

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppUserService users) throws Exception {
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
                .permitAll())
            // For the websocket handshake and for anything driving this without a
            // browser. The form login is what people use.
            .httpBasic(basic -> {})
            .csrf(c -> c
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(csrfHandler))
            .exceptionHandling(e -> e
                // The page is a single document that talks over fetch and a socket.
                // Redirecting an API call to a login form gives JavaScript an HTML
                // page where it expected JSON; a 401 is something it can act on.
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    request -> request.getRequestURI().startsWith("/api/"))
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
}
