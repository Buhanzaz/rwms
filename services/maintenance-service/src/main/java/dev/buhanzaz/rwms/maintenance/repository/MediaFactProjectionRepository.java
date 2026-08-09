package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for MediaFactProjection; business transitions remain in the owning service. */
public interface MediaFactProjectionRepository extends JpaRepository<MediaFactProjection, UUID> {}
