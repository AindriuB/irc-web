package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.github.aindriub.ircweb.irc.DirectoryService;
import io.github.aindriub.ircweb.irc.ProfileUpdate;
import io.github.aindriub.ircweb.irc.ServerProfile;
import io.github.aindriub.ircweb.irc.ServerUpsert;

/**
 * The directory, the profiles and the wall around them. No IRC server needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "irc-web.admin-username=tester",
            "irc-web.admin-password=test-password",
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-dirit",
            "spring.datasource.url=jdbc:h2:mem:irc-web-dirit;DB_CLOSE_DELAY=-1"
        })
class DirectoryAndSecurityIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DirectoryService directory;

    private TestRestTemplate asUser() {
        return rest.withBasicAuth("tester", "test-password");
    }

    /**
     * Headers carrying the CSRF token, taken the way a browser takes it: read the
     * cookie the server set, echo it back in the header. Driving the real path
     * rather than turning the protection off for tests means the tests would notice
     * if it stopped working.
     */
    private HttpHeaders csrf() {
        ResponseEntity<String> primer = asUser().getForEntity("/api/me", String.class);
        String setCookie = primer.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "expected the server to set an XSRF-TOKEN cookie");
        String token = setCookie.split(";")[0].split("=", 2)[1];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-XSRF-TOKEN", token);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        return headers;
    }

    private <T> ResponseEntity<T> send(HttpMethod method, String path, Object body,
            Class<T> type) {
        return asUser().exchange(path, method, new HttpEntity<>(body, csrf()), type);
    }

    @Test
    @DisplayName("a state-changing call without a CSRF token is refused")
    void requiresCsrfToken() {
        ResponseEntity<Map> response = asUser().postForEntity("/api/servers",
                new ServerUpsert("no-csrf", "No CSRF", "irc.test.invalid", 6667,
                        false, false, false, List.of(), null), Map.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "authenticated is not enough; the token has to be there too");
    }

    @Test
    @DisplayName("the API refuses anyone who is not signed in")
    void requiresAuthentication() {
        for (String path : List.of("/api/servers", "/api/me", "/api/servers/local/profile")) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                    path + " should not be readable without signing in");
        }
    }

    @Test
    @DisplayName("a browser asking for a page is sent to the login form, not a bare 401")
    void redirectsBrowsersToLogin() {
        // Only API calls should get a status code: JavaScript can act on a 401,
        // whereas a person looking at one has to work out what to do next.
        // TestRestTemplate follows the redirect, so this asserts on where it
        // lands rather than on the 302 itself.
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("action=\"/api/login\""),
                "a signed out browser should end up at the login form");
        assertFalse(response.getBody().contains("id=\"channel-tabs\""),
                "and should not be served the application itself");
    }

    @Test
    @DisplayName("the health probe works without credentials")
    void healthIsPublic() {
        // A container probe holds no credentials, so an authenticated health
        // endpoint reports every healthy container as unhealthy.
        ResponseEntity<Map> response = rest.getForEntity("/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertTrue(((Number) response.getBody().get("servers")).intValue() > 0,
                "a health check should say the configuration loaded, not just that it started");
    }

    @Test
    @DisplayName("the login page itself is reachable, or nobody could ever sign in")
    void loginPageIsPublic() {
        assertEquals(HttpStatus.OK, rest.getForEntity("/login.html", String.class)
                .getStatusCode());
    }

    @Test
    @DisplayName("the directory is seeded from servers.yml on first start")
    void seedsTheDirectory() {
        ResponseEntity<List> response = asUser().getForEntity("/api/servers", List.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().size() >= 9,
                "expected the seeded servers, got " + response.getBody().size());
    }

    @Test
    @DisplayName("a server can be added, edited and deleted")
    void serverLifecycle() {
        ServerUpsert created = new ServerUpsert("test-net", "Test Net", "irc.test.invalid",
                6697, true, true, false, List.of("tls"), "for the test");

        ResponseEntity<Map> post = send(HttpMethod.POST, "/api/servers", created, Map.class);
        assertEquals(HttpStatus.CREATED, post.getStatusCode());
        assertEquals("irc.test.invalid", post.getBody().get("host"));

        ServerUpsert edited = new ServerUpsert("test-net", "Test Net", "irc.changed.invalid",
                6667, false, false, false, List.of("plaintext"), "edited");
        send(HttpMethod.PUT, "/api/servers/test-net", edited, Map.class);
        assertEquals("irc.changed.invalid", directory.byId("test-net").orElseThrow().host());

        send(HttpMethod.DELETE, "/api/servers/test-net", null, Void.class);
        assertTrue(directory.byId("test-net").isEmpty());
    }

    @Test
    @DisplayName("an id that is already taken is refused rather than silently overwriting")
    void refusesDuplicateIds() {
        ServerUpsert duplicate = new ServerUpsert("local", "Another Local", "127.0.0.1",
                6667, false, false, false, List.of(), null);

        ResponseEntity<Map> response = send(HttpMethod.POST, "/api/servers", duplicate,
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("already exists"));
    }

    @Test
    @DisplayName("a profile round trips, and its passwords never come back out")
    void profileHidesSecrets() {
        directory.saveProfile("local", new ProfileUpdate("mynick", null, null,
                "server-secret", "myaccount", "sasl-secret", List.of("#one", "#two")));

        ResponseEntity<String> raw = asUser()
                .getForEntity("/api/servers/local/profile", String.class);
        ServerProfile profile = directory.profile("local");

        assertEquals("mynick", profile.nick());
        assertEquals(List.of("#one", "#two"), profile.channels());
        assertTrue(profile.hasPassword());
        assertTrue(profile.hasSaslPassword());

        // The wire format is what matters here: a field that is never serialised
        // cannot leak, and a boolean cannot be used to connect as someone.
        assertFalse(raw.getBody().contains("server-secret"),
                "the server password must never reach the browser");
        assertFalse(raw.getBody().contains("sasl-secret"),
                "the SASL password must never reach the browser");
        assertTrue(raw.getBody().contains("hasPassword"));
    }

    @Test
    @DisplayName("stored secrets survive a round trip through encryption")
    void secretsDecrypt() {
        directory.saveProfile("oftc", new ProfileUpdate("n", null, null,
                "the-password", "acct", "the-sasl", List.of()));

        DirectoryService.Credentials credentials = directory.credentials("oftc");

        assertEquals("the-password", credentials.password());
        assertEquals("the-sasl", credentials.saslPassword());
        assertEquals("acct", credentials.saslUsername());
    }

    @Test
    @DisplayName("an untouched password field leaves the stored one alone")
    void nullPasswordKeepsWhatIsStored() {
        directory.saveProfile("efnet", new ProfileUpdate("n", null, null,
                "original", null, null, List.of()));

        // What the browser sends when someone edits the nick and nothing else.
        directory.saveProfile("efnet", new ProfileUpdate("newnick", null, null,
                null, null, null, List.of()));

        assertEquals("newnick", directory.profile("efnet").nick());
        assertEquals("original", directory.credentials("efnet").password(),
                "editing another field must not wipe the password");
    }

    @Test
    @DisplayName("an empty password field clears the stored one")
    void emptyPasswordClears() {
        directory.saveProfile("rizon", new ProfileUpdate("n", null, null,
                "original", null, null, List.of()));

        directory.saveProfile("rizon", new ProfileUpdate("n", null, null,
                "", null, null, List.of()));

        assertFalse(directory.profile("rizon").hasPassword());
        assertNull(directory.credentials("rizon").password());
    }

    @Test
    @DisplayName("deleting a server takes its stored credentials with it")
    void deletingAServerRemovesItsProfile() {
        send(HttpMethod.POST, "/api/servers", new ServerUpsert("doomed", "Doomed",
                "irc.doomed.invalid", 6667, false, false, false, List.of(), null), Map.class);
        directory.saveProfile("doomed", new ProfileUpdate("n", null, null,
                "a-secret", null, null, List.of()));
        assertTrue(directory.profile("doomed").hasPassword());

        send(HttpMethod.DELETE, "/api/servers/doomed", null, Void.class);

        // An orphaned credential for a server nobody remembers is worse than none.
        assertFalse(directory.profile("doomed").hasPassword());
        assertNull(directory.credentials("doomed").password());
    }

    @Test
    @DisplayName("who am I reports the signed-in user")
    void reportsTheCurrentUser() {
        ResponseEntity<Map> response = asUser().getForEntity("/api/me", Map.class);

        assertEquals("tester", response.getBody().get("username"));
        assertNotNull(response.getBody().get("generatedPassword"));
    }

    @Test
    @DisplayName("a bad port is refused with a message rather than a stack trace")
    void validatesInput() {
        ResponseEntity<Map> response = send(HttpMethod.POST, "/api/servers",
                new ServerUpsert("bad-port", "Bad", "irc.test.invalid", 99999,
                        false, false, false, List.of(), null), Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("port"));
    }
}
