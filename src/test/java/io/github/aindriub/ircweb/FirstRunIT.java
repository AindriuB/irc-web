package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * An installation nobody has set up yet.
 *
 * <p>Ordered, because the whole point is that the application changes state once:
 * everything is closed, someone chooses an account, and then setup is gone. Testing
 * those as independent cases would not test the transition, which is the part that
 * has to be right.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // No irc-web.admin-password on purpose: that is what "not set up" means.
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-firstrun",
            "spring.datasource.url=jdbc:h2:mem:irc-web-firstrun;DB_CLOSE_DELAY=-1"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstRunIT {

    /** Only the setup page carries this form. */
    private static final String SETUP_PAGE_MARKER = "id=\"setup\"";

    private static final String USERNAME = "operator";
    private static final String PASSWORD = "a-chosen-password";

    @LocalServerPort
    private int port;

    private RestTemplate rest;

    @BeforeEach
    void client() {
        rest = TestHttp.anonymous(port);
    }

    private HttpHeaders csrf() {
        ResponseEntity<String> primer = rest.getForEntity("/api/setup", String.class);
        String setCookie = primer.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "expected the server to set an XSRF-TOKEN cookie");
        String token = setCookie.split(";")[0].split("=", 2)[1];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-XSRF-TOKEN", token);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        return headers;
    }

    @Test
    @Order(1)
    @DisplayName("a browser arriving at anything is sent to setup")
    void everyPageGoesToSetup() {
        // The template follows redirects, as a browser does, so what is asserted is
        // where you end up rather than the status on the way.
        for (String path : List.of("/", "/index.html", "/style.css")) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);

            assertEquals(HttpStatus.OK, response.getStatusCode(), path);
            assertTrue(response.getBody().contains(SETUP_PAGE_MARKER),
                    path + " should have landed on the setup page");
        }
    }

    @Test
    @Order(2)
    @DisplayName("the API says what is wrong rather than redirecting a script to HTML")
    void theApiRefusesWithAReason() {
        ResponseEntity<Map> response = rest.getForEntity("/api/servers", Map.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("set up"));
    }

    @Test
    @Order(3)
    @DisplayName("the setup page itself is reachable, and says setup is needed")
    void setupIsReachable() {
        ResponseEntity<String> page = rest.getForEntity("/setup.html", String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertTrue(page.getBody().contains(SETUP_PAGE_MARKER));

        ResponseEntity<Map> state = rest.getForEntity("/api/setup", Map.class);
        assertEquals(Boolean.TRUE, state.getBody().get("required"));
    }

    @Test
    @Order(4)
    @DisplayName("a password too short to be worth having is refused")
    void refusesAWeakPassword() {
        ResponseEntity<Map> response = rest.exchange("/api/setup", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", USERNAME, "password", "short"), csrf()),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("8 characters"));
    }

    @Test
    @Order(5)
    @DisplayName("choosing an account opens the application, and closes setup")
    void setupLetsYouIn() {
        ResponseEntity<Void> created = rest.exchange("/api/setup", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", USERNAME, "password", PASSWORD), csrf()),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, created.getStatusCode());

        // The account works.
        ResponseEntity<Map> me = TestHttp.as(port, USERNAME, PASSWORD)
                .getForEntity("/api/me", Map.class);
        assertEquals(USERNAME, me.getBody().get("username"));

        // And the wall is back up for everyone else: not a redirect to setup, an
        // ordinary unauthenticated 401.
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.getForEntity("/api/me", String.class).getStatusCode());

        // Setup cannot be used twice.
        assertEquals(HttpStatus.CONFLICT, rest.exchange("/api/setup", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "second", "password", "another-password"),
                        csrf()),
                Map.class).getStatusCode());
    }
}
