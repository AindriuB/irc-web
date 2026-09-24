package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Remember-me: the socket reopens long after the session that created it has
 * expired, so what carries it back in is the persistent-token cookie, not the
 * session cookie. And a signed-out socket should say so with a 401, not hand
 * back a login page a script cannot read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "irc-web.admin-username=tester",
            "irc-web.admin-password=test-password",
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-remembermeit",
            "spring.datasource.url=jdbc:h2:mem:irc-web-remembermeit;DB_CLOSE_DELAY=-1"
        })
class RememberMeIT {

    private static final long TIMEOUT_SECONDS = 10;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    /**
     * Signs in as the form actually submits, with {@code remember-me=on}, and
     * returns the {@code remember-me} cookie value the server set. No redirects
     * followed: the assertion is on the cookie the login response carried, and
     * following it would mean a second, unrelated request in between.
     */
    private String logInAndGetRememberMeCookie() {
        RestTemplate rest = TestHttp.anonymousNoRedirects(port);
        ResponseEntity<String> page = rest.getForEntity("/login.html", String.class);
        String xsrfCookie = page.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String token = xsrfCookie.split(";")[0].split("=", 2)[1];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "tester");
        form.add("password", "test-password");
        form.add("remember-me", "on");
        form.add("_csrf", token);

        ResponseEntity<String> response = rest.exchange("/api/login", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);

        assertEquals(HttpStatus.FOUND, response.getStatusCode(),
                "expected sign-in to succeed and redirect");

        String cookie = cookieValue(response.getHeaders().get(HttpHeaders.SET_COOKIE),
                "remember-me");
        assertNotNull(cookie, "expected the login response to set a remember-me cookie");
        return cookie;
    }

    private static String cookieValue(List<String> setCookieHeaders, String name) {
        if (setCookieHeaders == null) {
            return null;
        }
        for (String header : setCookieHeaders) {
            String pair = header.split(";")[0];
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private static String cookieHeaderStartingWith(HttpHeaders headers, String prefix) {
        List<String> setCookieHeaders = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookieHeaders == null) {
            return null;
        }
        for (String header : setCookieHeaders) {
            if (header.startsWith(prefix)) {
                return header;
            }
        }
        return null;
    }

    private HttpHeaders rememberMeOnlyHeaders(String rememberMeCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "remember-me=" + rememberMeCookie);
        return headers;
    }

    @Test
    @DisplayName("a remember-me cookie alone authenticates a later request, no session needed")
    void rememberMeCookieAuthenticatesWithoutASession() {
        String rememberMeCookie = logInAndGetRememberMeCookie();

        RestTemplate rest = TestHttp.anonymousNoRedirects(port);
        ResponseEntity<Map> me = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(rememberMeOnlyHeaders(rememberMeCookie)), Map.class);

        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals("tester", me.getBody().get("username"));
    }

    @Test
    @DisplayName("a websocket handshake carrying only the remember-me cookie connects")
    void rememberMeCookieAuthenticatesTheWebSocketHandshake() throws Exception {
        String rememberMeCookie = logInAndGetRememberMeCookie();

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, "remember-me=" + rememberMeCookie);

        Recorder recorder = new Recorder();
        WebSocketSession socket = new StandardWebSocketClient()
                .execute(recorder, headers, URI.create("ws://localhost:" + port + "/ws/irc"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try {
            assertTrue(socket.isOpen(), "expected the handshake to succeed and stay open");
            assertFalse(recorder.closedWith1008(), "the server should not have rejected it");
        } finally {
            if (socket.isOpen()) {
                socket.close(CloseStatus.NORMAL);
            }
        }
    }

    @Test
    @DisplayName("an unauthenticated websocket handshake gets 401, not a redirect")
    void unauthenticatedWebSocketGets401() {
        RestTemplate rest = TestHttp.anonymousNoRedirects(port);

        ResponseEntity<String> ws = rest.getForEntity("/ws/irc", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, ws.getStatusCode(),
                "a signed-out socket reopen should be told so with a status, not a redirect");

        ResponseEntity<String> page = rest.getForEntity("/", String.class);
        assertEquals(HttpStatus.FOUND, page.getStatusCode(),
                "a signed-out browser should still be redirected to the login form");
        assertTrue(page.getHeaders().getLocation().getPath().endsWith("/login.html"),
                "expected a redirect to the login page");
    }

    @Test
    @DisplayName("logging out deletes the persistent-login rows and the old cookie stops working")
    void logoutDeletesPersistentLoginsAndInvalidatesTheCookie() {
        String rememberMeCookie = logInAndGetRememberMeCookie();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertTrue(rowCountForTester(jdbc) > 0,
                "expected the login to have left a row in persistent_logins");

        RestTemplate rest = TestHttp.anonymousNoRedirects(port);

        // Logout is state-changing, so it needs the CSRF token too. Primed the
        // same way the browser gets one: an authenticated GET sets the cookie. A
        // remember-me auto-login also rotates the token, so what carries forward
        // is the cookie this response set, not the one that came from signing in.
        HttpHeaders primerHeaders = rememberMeOnlyHeaders(rememberMeCookie);
        ResponseEntity<Map> primer = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(primerHeaders), Map.class);
        List<String> primerCookies = primer.getHeaders().get(HttpHeaders.SET_COOKIE);
        String csrfToken = cookieValue(primerCookies, "XSRF-TOKEN");
        String rotatedRememberMeCookie = cookieValue(primerCookies, "remember-me");
        assertNotNull(csrfToken, "expected an authenticated request to set an XSRF-TOKEN cookie");
        assertNotNull(rotatedRememberMeCookie, "expected auto-login to rotate the token");

        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.add(HttpHeaders.COOKIE,
                "remember-me=" + rotatedRememberMeCookie + "; XSRF-TOKEN=" + csrfToken);
        logoutHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<String> logout = rest.exchange("/api/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), String.class);

        assertEquals(HttpStatus.FOUND, logout.getStatusCode());
        String clearedCookie = cookieHeaderStartingWith(logout.getHeaders(), "remember-me=");
        assertNotNull(clearedCookie, "expected logout to clear the remember-me cookie");
        // A servlet-cleared cookie carries a past Expires rather than a literal
        // "Max-Age=0" - both tell the browser to drop it now, which is the
        // behaviour under test.
        assertTrue(clearedCookie.contains("Expires=Thu, 01 Jan 1970"),
                "expected the cookie to be cleared, was: " + clearedCookie);

        assertEquals(0, rowCountForTester(jdbc),
                "expected logout to remove every persistent_logins row for the user");

        ResponseEntity<Map> me = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(rememberMeOnlyHeaders(rememberMeCookie)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, me.getStatusCode(),
                "the old remember-me cookie should no longer authenticate anything");
    }

    @Test
    @DisplayName("the right series with the wrong token deletes nothing")
    void wrongTokenWithRightSeriesDeletesNothing() {
        String rememberMeCookie = logInAndGetRememberMeCookie();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int before = rowCountForTester(jdbc);
        assertTrue(before > 0, "expected the login to have left a row in persistent_logins");

        String series = decodeRememberMeCookie(rememberMeCookie)[0];
        String forgedCookie = encodeRememberMeCookie(series, "not-the-real-token");

        RestTemplate rest = TestHttp.anonymousNoRedirects(port);

        // CSRF primed with the real cookie, so the only thing under test is the
        // remember-me cookie sent with the logout call itself.
        ResponseEntity<Map> primer = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(rememberMeOnlyHeaders(rememberMeCookie)), Map.class);
        String csrfToken = cookieValue(primer.getHeaders().get(HttpHeaders.SET_COOKIE),
                "XSRF-TOKEN");
        assertNotNull(csrfToken);

        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.add(HttpHeaders.COOKIE,
                "remember-me=" + forgedCookie + "; XSRF-TOKEN=" + csrfToken);
        logoutHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<String> logout = rest.exchange("/api/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), String.class);

        assertFalse(logout.getStatusCode().is5xxServerError(),
                "a forged token must not blow up the logout call, was: "
                        + logout.getStatusCode());
        assertEquals(before, rowCountForTester(jdbc),
                "knowing only the series must not be enough to delete another "
                        + "session's persistent_logins rows");
    }

    @Test
    @DisplayName("a malformed remember-me cookie does not crash the logout call")
    void malformedRememberMeCookieDoesNotErrorLogout() {
        RestTemplate rest = TestHttp.anonymousNoRedirects(port);
        ResponseEntity<String> page = rest.getForEntity("/login.html", String.class);
        String xsrfCookie = page.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String csrfToken = xsrfCookie.split(";")[0].split("=", 2)[1];

        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.add(HttpHeaders.COOKIE,
                "remember-me=not-valid-base64!!!; XSRF-TOKEN=" + csrfToken);
        logoutHeaders.add("X-XSRF-TOKEN", csrfToken);

        ResponseEntity<String> logout = rest.exchange("/api/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), String.class);

        assertFalse(logout.getStatusCode().is5xxServerError(),
                "a malformed cookie must not crash logout, was: " + logout.getStatusCode());
    }

    /** [series, token], both already URL-decoded. */
    private static String[] decodeRememberMeCookie(String cookieValue) {
        String value = cookieValue;
        while (value.length() % 4 != 0) {
            value = value + "=";
        }
        byte[] bytes = Base64.getDecoder().decode(value);
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
        return new String[] {
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
            URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
        };
    }

    /** The same encoding {@code AbstractRememberMeServices} uses for the cookie. */
    private static String encodeRememberMeCookie(String series, String token) {
        String joined = URLEncoder.encode(series, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static int rowCountForTester(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "select count(*) from persistent_logins where username = ?",
                Integer.class, "tester");
        return count == null ? 0 : count;
    }

    /** Records how a socket was closed, if it was. */
    private static final class Recorder extends TextWebSocketHandler {

        private volatile CloseStatus closeStatus;

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.closeStatus = status;
        }

        boolean closedWith1008() {
            CloseStatus status = closeStatus;
            return status != null && status.getCode() == CloseStatus.POLICY_VIOLATION.getCode();
        }
    }
}
