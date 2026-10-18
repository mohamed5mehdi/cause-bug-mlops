package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Aucun verrou explicite ici : la protection contre les mises a jour concurrentes
 * repose sur le champ {@code @Version} de l'entite (voir justification dans
 * {@link DemandeAchatInterne}).
 */
public interface DemandeAchatInterneRepository extends JpaRepository<DemandeAchatInterne, UUID> {

    /**
     * Lecture complete pour l'API : charge en une seule requete le demandeur, les
     * lignes et leurs articles, qui sont sinon charges a la demande (lazy). Sans
     * cela, la conversion en DTO hors transaction echouerait
     * (LazyInitializationException, car open-in-view est desactive).
     */
    @EntityGraph(attributePaths = {"demandeur", "lignes", "lignes.article"})
    Optional<DemandeAchatInterne> findDetailById(UUID id);
}
