package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.domain.common.UrgenceDemande;
import com.causebugmlops.erp.dto.LigneDemandeDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Formulaire HTTP de creation d'une demande d'Achat Interne (POST /api/demandes).
 * <p>
 * Les annotations de validation sont verifiees par Spring AVANT d'appeler le
 * service ({@code @Valid} dans le controleur) : une requete incomplete est
 * refusee en 400 sans toucher a la base.
 *
 * @param urgence       facultative : NORMALE par defaut (regle appliquee par le service)
 * @param justification motif de la demande
 * @param lignes        au moins une ligne
 */
public record CreerDemandeRequest(
        UrgenceDemande urgence,
        @NotBlank @Size(max = 2000) String justification,
        @NotEmpty @Valid List<Ligne> lignes
) {

    /**
     * Une ligne de la demande. Soit un article du catalogue ({@code articleId}),
     * soit un besoin en texte libre ({@code designationLibre}).
     */
    public record Ligne(
            Long articleId,
            @Size(max = 200) String designationLibre,
            @Size(max = 500) String specification,
            @NotNull @Positive Integer quantite,
            @Size(max = 500) String justificationLigne
    ) {
        /** Conversion vers le DTO deja attendu par la couche service. */
        public LigneDemandeDto versDtoService() {
            return new LigneDemandeDto(null, articleId, designationLibre, specification,
                    quantite, justificationLigne, null);
        }
    }
}
