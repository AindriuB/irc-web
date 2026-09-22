package io.github.aindriub.ircweb.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.aindriub.ircweb.store.AppUserEntity;
import io.github.aindriub.ircweb.store.AppUserRepository;

@Service
public class AppUserService implements UserDetailsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppUserService.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String initialUsername;
    private final String initialPassword;

    public AppUserService(AppUserRepository users, PasswordEncoder encoder,
            @Value("${irc-web.admin-username:admin}") String initialUsername,
            @Value("${irc-web.admin-password:}") String initialPassword) {
        this.users = users;
        this.encoder = encoder;
        this.initialUsername = initialUsername;
        this.initialPassword = initialPassword;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUserEntity user = users.findById(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles("USER")
                .build();
    }

    /**
     * Creates the first account if there is none. Runs after the context is ready so
     * the generated password is the last thing in the log rather than buried.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createInitialUser() {
        if (users.count() > 0) {
            return;
        }
        boolean generated = initialPassword == null || initialPassword.isBlank();
        String password = generated ? randomPassword() : initialPassword;
        users.save(new AppUserEntity(initialUsername, encoder.encode(password), generated));

        if (generated) {
            LOGGER.warn("""

                    ================================================================
                     No irc-web.admin-password was set, so one has been generated:

                         username: {}
                         password: {}

                     This is printed once. Change it in the UI, or set
                     IRC_WEB_ADMIN_PASSWORD and delete the data directory to start
                     again. A generated password that is never changed is how these
                     end up effectively unauthenticated.
                    ================================================================
                    """, initialUsername, password);
        } else {
            LOGGER.info("Created the initial account '{}' from configuration", initialUsername);
        }
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        AppUserEntity user = users.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("the current password is wrong");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("the new password must be at least 8 characters");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setGeneratedPassword(false);
        users.save(user);
        LOGGER.info("Password changed for '{}'", username);
    }

    public boolean hasGeneratedPassword(String username) {
        return users.findById(username).map(AppUserEntity::isGeneratedPassword).orElse(false);
    }

    public List<AppUserEntity> all() {
        return users.findAll();
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
