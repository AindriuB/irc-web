package io.github.aindriub.ircweb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * An HTTP client for the tests, pointed at the running application.
 *
 * <p>Spring Boot 4 removed {@code TestRestTemplate} in favour of a fluent client.
 * What the tests actually needed from it was three things — a base URL, optional
 * credentials, and a 4xx that comes back as a response rather than an exception,
 * since the status is usually the assertion. A {@link RestTemplate} configured
 * those three ways has the same method signatures, so the tests say what they said
 * before rather than being rewritten around a new API.
 */
final class TestHttp {

    private TestHttp() {
    }

    /** Nobody signed in, for asserting that the wall is there. */
    static RestTemplate anonymous(int port) {
        return build(port, null, null, true);
    }

    static RestTemplate as(int port, String username, String password) {
        return build(port, username, password, true);
    }

    /**
     * Nobody signed in, and a redirect comes back as a redirect. Everything else
     * here follows one, which is right for a browser but hides the status code a
     * test on "/ws/**" vs "/" needs to tell 401 from 302 apart.
     */
    static RestTemplate anonymousNoRedirects(int port) {
        return build(port, null, null, false);
    }

    private static RestTemplate build(int port, String username, String password,
            boolean followRedirects) {
        SimpleClientHttpRequestFactory factory = followRedirects
                ? new SimpleClientHttpRequestFactory()
                : new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection,
                            String httpMethod) throws IOException {
                        super.prepareConnection(connection, httpMethod);
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        RestTemplate rest = new RestTemplate(factory);
        rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));

        // A 401 or a 404 is something a test asserts on, not something that should
        // blow up before it can.
        rest.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });

        if (username != null) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            rest.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().add(HttpHeaders.AUTHORIZATION, "Basic " + token);
                return execution.execute(request, body);
            });
        }
        return rest;
    }
}
