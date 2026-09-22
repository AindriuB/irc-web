package io.github.aindriub.ircweb.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.aindriub.ircweb.security.AppUserService;

/**
 * First run: choosing the account that everything else is behind.
 *
 * <p>The only endpoint reachable before there is anyone to sign in as, and it
 * closes behind itself the moment it succeeds.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final AppUserService users;

    public SetupController(AppUserService users) {
        this.users = users;
    }

    @GetMapping
    public Map<String, Object> state() {
        return Map.of(
                "required", users.needsSetup(),
                "minimumPasswordLength", AppUserService.MINIMUM_PASSWORD_LENGTH);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@RequestBody Map<String, String> body) {
        users.completeSetup(body.get("username"), body.get("password"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Someone else got there first, or a stale tab was submitted twice. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> alreadyDone(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
