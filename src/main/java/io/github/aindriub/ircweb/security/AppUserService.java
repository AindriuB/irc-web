package io.github.aindriub.ircweb.security;

import java.util.concurrent.atomic.AtomicBoolean;

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

/**
 * The accounts allowed to use this application.
 *
 * <p>There is no default password. An installation with no account at all is not
 * half open, it is closed: {@link #needsSetup()} is true and
 * {@link SetupRequiredFilter} sends every request to the setup page until a human
 * has chosen a username and password. A default that is never changed, or a
 * generated one printed to a log that later rotates away, are the two ways this
 * usually goes wrong, and neither is possible here.
 *
 * <p>Setting {@code IRC_WEB_ADMIN_PASSWORD} before the first start creates the
 * account up front and skips setup, which is what a compose file or CI should do.
 */
@Service
public class AppUserService implements UserDetailsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppUserService.class);

    /** Short enough not to be annoying, long enough not to be guessed in a hurry. */
    public static final int MINIMUM_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String initialUsername;
    private final String initialPassword;

    /**
     * Remembers that setup is done, so the common case costs nothing. It only ever
     * goes false to true: an account cannot be un-created, so a cached true can
     * never be stale.
     */
    private final AtomicBoolean setUp = new AtomicBoolean();

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
     * Creates the account from configuration, if it was configured and there is
     * none. With no configured password this does nothing at all and the first
     * visitor is sent to setup.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createConfiguredUser() {
        if (users.count() > 0) {
            setUp.set(true);
            return;
        }
        if (initialPassword == null || initialPassword.isBlank()) {
            LOGGER.info("No account exists yet. The first browser to arrive will be "
                    + "asked to create one; set IRC_WEB_ADMIN_PASSWORD to skip that.");
            return;
        }
        users.save(new AppUserEntity(initialUsername, encoder.encode(initialPassword)));
        setUp.set(true);
        LOGGER.info("Created the account '{}' from configuration", initialUsername);
    }

    /** Whether nobody has created an account yet, so the setup page is the only page. */
    @Transactional(readOnly = true)
    public boolean needsSetup() {
        if (setUp.get()) {
            return false;
        }
        boolean exists = users.count() > 0;
        if (exists) {
            setUp.set(true);
        }
        return !exists;
    }

    /**
     * Creates the first account. Refuses once one exists, so the setup page cannot
     * be used to mint a second account on an application already in use.
     */
    @Transactional
    public void completeSetup(String username, String password) {
        if (users.count() > 0) {
            throw new IllegalStateException("this application has already been set up");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("a username is required");
        }
        requireAcceptable(password);
        users.save(new AppUserEntity(username.trim(), encoder.encode(password)));
        setUp.set(true);
        LOGGER.info("Setup completed, account '{}' created", username.trim());
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        AppUserEntity user = users.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("the current password is wrong");
        }
        requireAcceptable(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);
        LOGGER.info("Password changed for '{}'", username);
    }

    private static void requireAcceptable(String password) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "the password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters");
        }
    }
}
