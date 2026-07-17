package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFactProjectionRepository extends JpaRepository<MediaFactProjection, UUID> {}
