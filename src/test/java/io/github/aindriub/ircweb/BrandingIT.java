package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    /**
     * Sends a request line built by hand over a raw socket, bypassing any
     * normalisation an HTTP client like {@link RestTemplate} (or the {@link java.net.URI}
     * it builds one from) would apply to a target containing {@code ..}. What is
     * asserted here is what the server does with the bytes as sent, not with
     * whatever a well-behaved client would have turned them into first.
     */
    private int rawGetStatus(String target) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            assertNotNull(statusLine, "expected a response to " + target
                    + " rather than the connection just closing");
            // "HTTP/1.1 400 Bad Request"
            String[] parts = statusLine.split(" ", 3);
            return Integer.parseInt(parts[1]);
        }
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
    @DisplayName("before setup, the API says what is wrong rather than redirecting a script to HTML")
    void apiRefusedBeforeSetup() {
        ResponseEntity<Map> response = rest.getForEntity("/api/servers", Map.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("set up"));
    }

    @Test
    @Order(3)
    @DisplayName("before setup, a crafted /brand/ target cannot reach what it is not")
    void brandPrefixCannotBeSmuggledPast() throws IOException {
        for (String target : List.of("/brand/..;/index.html", "/brand/%2e%2e/app.js",
                "/brand/../api/servers")) {
            int status = rawGetStatus(target);
            assertTrue(status == 400 || (status >= 300 && status < 400),
                    target + " should have been refused (400) or redirected, not answered as "
                            + status);
        }
    }

    @Test
    @Order(4)
    @DisplayName("choosing an account opens the application")
    void createAccount() {
        ResponseEntity<Void> created = rest.exchange("/api/setup", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", USERNAME, "password", PASSWORD), csrf()),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, created.getStatusCode());
    }

    @Test
    @Order(5)
    @DisplayName("after setup, brand assets and the manifest are still reachable without credentials")
    void reachableAfterSetup() {
        assertBrandAssetsReachable();
    }

    @Test
    @Order(6)
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

    @Test
    @Order(7)
    @DisplayName("after setup, the API refuses an unauthenticated caller outright")
    void apiRefusedAfterSetup() {
        ResponseEntity<String> response = rest.getForEntity("/api/servers", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
