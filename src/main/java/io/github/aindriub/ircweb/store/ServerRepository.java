package io.github.aindriub.ircweb.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerRepository extends JpaRepository<ServerEntity, String> {

    List<ServerEntity> findAllByOrderByNameAsc();
}
