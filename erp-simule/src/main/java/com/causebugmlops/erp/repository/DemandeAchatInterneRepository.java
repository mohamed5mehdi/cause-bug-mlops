package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Aucun verrou explicite ici : la protection contre les mises a jour concurrentes
 * repose sur le champ {@code @Version} de l'entite (voir justification dans
 * {@link DemandeAchatInterne}).
 */
public interface DemandeAchatInterneRepository extends JpaRepository<DemandeAchatInterne, UUID> {
}
