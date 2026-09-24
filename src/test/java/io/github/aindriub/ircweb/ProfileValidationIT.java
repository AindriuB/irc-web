package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import io.github.aindriub.ircweb.irc.CredentialRules;
import io.github.aindriub.ircweb.irc.DirectoryService;
import io.github.aindriub.ircweb.irc.ProfileUpdate;

/**
 * A profile save is refused, with a clear reason and no echo, when the
 * credentials it carries are ones IRC itself cannot transmit. No IRC server
 * needed: this never gets as far as connecting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "irc-web.admin-username=tester",
            "irc-web.admin-password=test-password",
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-profilevalidationit",
            "spring.datasource.url=jdbc:h2:mem:irc-web-profilevalidationit;DB_CLOSE_DELAY=-1"
        })
class ProfileValidationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private DirectoryService directory;

    private RestTemplate asUser() {
        return TestHttp.as(port, "tester", "test-password");
    }

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

    private <T> ResponseEntity<T> put(String path, Object body, Class<T> type) {
        return asUser().exchange(path, HttpMethod.PUT, new HttpEntity<>(body, csrf()), type);
    }

    @BeforeEach
    void aServerExists() {
        // The seeded directory (servers.yml) already provides "local"; nothing to set up.
    }

    @Test
    @DisplayName("a server password that is a whole PASS line is rejected, and never echoed")
    void passLineRejected() {
        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("n", null, null, "PASS oauth:sekrit-token-123",
                        null, null, List.of()),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(CredentialRules.PASS_LINE_HINT, response.getBody().get("error"));
        assertFalse(String.valueOf(response.getBody()).contains("sekrit-token-123"));
    }

    @Test
    @DisplayName("a SASL password with a space is accepted")
    void saslPasswordWithSpaceAccepted() {
        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("n", null, null, null, "acct", "has space", List.of()),
                Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("a SASL password with a line break is rejected, and never echoed")
    void saslPasswordWithLineBreakRejected() {
        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("n", null, null, null, "acct", "has\nbreak", List.of()),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("The SASL password cannot contain spaces or line breaks",
                response.getBody().get("error"));
        assertFalse(String.valueOf(response.getBody()).contains("has\nbreak"));
    }

    @Test
    @DisplayName("a rejected save leaves the stored profile unchanged")
    void rejectedSaveLeavesProfileUnchanged() {
        directory.saveProfile("local", new ProfileUpdate("n", null, null,
                "original-password", null, null, List.of()));
        boolean before = directory.profile("local").hasPassword();

        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("n", null, null, "bad password", null, null, List.of()),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(before, directory.profile("local").hasPassword());
        assertEquals("original-password", directory.credentials("local").password());
    }

    @Test
    @DisplayName("a null password field leaves an existing password alone")
    void nullPasswordKeepsExisting() {
        directory.saveProfile("local", new ProfileUpdate("n", null, null,
                "kept-password", null, null, List.of()));

        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("newnick", null, null, null, null, null, List.of()),
                Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(directory.profile("local").hasPassword());
        assertEquals("kept-password", directory.credentials("local").password());
    }

    @Test
    @DisplayName("an empty password field clears an existing password")
    void emptyPasswordClearsExisting() {
        directory.saveProfile("local", new ProfileUpdate("n", null, null,
                "to-be-cleared", null, null, List.of()));

        ResponseEntity<Map> response = put("/api/servers/local/profile",
                new ProfileUpdate("n", null, null, "", null, null, List.of()),
                Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(directory.profile("local").hasPassword());
    }
}
