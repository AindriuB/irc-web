package io.github.aindriub.ircweb.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.aindriub.ircweb.irc.DirectoryService;
import io.github.aindriub.ircweb.irc.IrcServer;
import io.github.aindriub.ircweb.irc.ProfileUpdate;
import io.github.aindriub.ircweb.irc.ServerProfile;
import io.github.aindriub.ircweb.irc.ServerUpsert;
import io.github.aindriub.ircweb.security.AppUserService;
import jakarta.validation.Valid;

/**
 * The directory, the profiles attached to it, and who is logged in.
 */
@RestController
@RequestMapping("/api")
public class ServerDirectoryController {

    private final DirectoryService directory;
    private final AppUserService users;

    public ServerDirectoryController(DirectoryService directory, AppUserService users) {
        this.directory = directory;
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Principal principal) {
        return Map.of("username", principal.getName());
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Principal principal, @RequestBody Map<String, String> body) {
        users.changePassword(principal.getName(), body.get("currentPassword"),
                body.get("newPassword"));
    }

    @GetMapping("/servers")
    public List<IrcServer> servers() {
        return directory.list();
    }

    @PostMapping("/servers")
    @ResponseStatus(HttpStatus.CREATED)
    public IrcServer create(@Valid @RequestBody ServerUpsert request) {
        return directory.create(request);
    }

    @PutMapping("/servers/{id}")
    public IrcServer update(@PathVariable String id, @Valid @RequestBody ServerUpsert request) {
        return directory.update(id, request);
    }

    @DeleteMapping("/servers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        directory.delete(id);
    }

    @GetMapping("/servers/{id}/profile")
    public ServerProfile profile(@PathVariable String id) {
        return directory.profile(id);
    }

    @PutMapping("/servers/{id}/profile")
    public ServerProfile saveProfile(@PathVariable String id,
            @RequestBody ProfileUpdate update) {
        return directory.saveProfile(id, update);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .findFirst().orElse("invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", detail));
    }
}
