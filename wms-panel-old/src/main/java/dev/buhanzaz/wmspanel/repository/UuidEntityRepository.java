package dev.buhanzaz.wmspanel.repository;

import dev.buhanzaz.wmspanel.entity.UuidEntity;
import io.jmix.core.repository.JmixDataRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

@NoRepositoryBean
public interface UuidEntityRepository<T extends UuidEntity> extends JmixDataRepository<T, UUID> {
}
