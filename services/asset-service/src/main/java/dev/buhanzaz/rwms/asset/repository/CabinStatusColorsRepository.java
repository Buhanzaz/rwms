package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.CabinStatusColors;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence of the single version-fenced global cabin palette. */
public interface CabinStatusColorsRepository extends JpaRepository<CabinStatusColors, UUID> {}
