package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Favicons and the manifest are fetched by the browser itself, not by anyone
 * signed in - so they have to work both before an account exists and after,
 * without credentials either time.
 *
 * <p>Ordered like {@link FirstRunIT}, for the same reason: the interesting
 * moment is the transition from "no account" to "an account", and this checks
 * the brand assets keep working across it while the rest of the application
 * does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // No irc-web.admin-password on purpose: that is what "not set up" means.
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-branding",
            "spring.datasource.url=jdbc:h2:mem:irc-web-branding;DB_CLOSE_DELAY=-1"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrandingIT {

    /** Only the setup page carries this form. */
    private static final String SETUP_PAGE_MARKER = "id=\"setup\"";

    private static final String USERNAME = "operator";
    private static final String PASSWORD = "a-chosen-password";

    @LocalServerPort
    private int port;

    private RestTemplate rest;
    private RestTemplate restNoRedirects;

    @BeforeEach
    void client() {
        rest = TestHttp.anonymous(port);
        restNoRedirects = TestHttp.anonymousNoRedirects(port);
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

    private void assertBrandAssetsReachable() {
        ResponseEntity<byte[]> favicon = rest.getForEntity("/brand/favicon.svg", byte[].class);
        assertEquals(HttpStatus.OK, favicon.getStatusCode());
        assertEquals(MediaType.valueOf("image/svg+xml"), favicon.getHeaders().getContentType());

        ResponseEntity<byte[]> icon = rest.getForEntity("/brand/icon-192.png", byte[].class);
        assertEquals(HttpStatus.OK, icon.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, icon.getHeaders().getContentType());

        ResponseEntity<byte[]> manifest = rest.getForEntity("/manifest.webmanifest", byte[].class);
        assertEquals(HttpStatus.OK, manifest.getStatusCode());
        assertEquals(MediaType.valueOf("application/manifest+json"),
                manifest.getHeaders().getContentType());
    }

    @Test
    @Order(1)
    @DisplayName("before setup, brand assets and the manifest are reachable but the app is not")
    void reachableBeforeSetup() {
        assertBrandAssetsReachable();

        ResponseEntity<String> index = rest.getForEntity("/index.html", String.class);
        assertEquals(HttpStatus.OK, index.getStatusCode());
        assertTrue(index.getBody().contains(SETUP_PAGE_MARKER),
                "/index.html should have landed on the setup page, not the application");
    }

    @Test
    @Order(2)
    @DisplayName("choosing an account opens the application")
    void createAccount() {
        ResponseEntity<Void> created = rest.exchange("/api/setup", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", USERNAME, "password", PASSWORD), csrf()),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, created.getStatusCode());
    }

    @Test
    @Order(3)
    @DisplayName("after setup, brand assets and the manifest are still reachable without credentials")
    void reachableAfterSetup() {
        assertBrandAssetsReachable();
    }

    @Test
    @Order(4)
    @DisplayName("after setup, the application itself still requires signing in")
    void appStillRequiresLogin() {
        ResponseEntity<Void> index = restNoRedirects.getForEntity("/index.html", Void.class);
        assertTrue(index.getStatusCode().is3xxRedirection(),
                "/index.html should redirect an unauthenticated browser to /login.html");

        ResponseEntity<Void> appJs = restNoRedirects.getForEntity("/app.js", Void.class);
        assertTrue(appJs.getStatusCode().is3xxRedirection()
                        || appJs.getStatusCode() == HttpStatus.UNAUTHORIZED,
                "/app.js should not be handed to an unauthenticated browser");
    }
}
