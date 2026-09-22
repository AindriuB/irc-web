package io.github.aindriub.ircweb.store;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<ProfileEntity, String> {

    Optional<ProfileEntity> findByServerId(String serverId);

    void deleteByServerId(String serverId);
}
