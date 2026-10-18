package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.dto.LigneDemandeDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * Valorisation par l'Acheteur : prix des lignes, taux de TVA, fournisseur retenu et
 * sous-famille budgetaire a engager.
 * <p>
 * Le taux de TVA est une FRACTION (0.20 pour 20 %). Les bornes [0 ; 1] refusent en 400
 * une valeur saisie en pourcentage (20), qui multiplierait le montant TTC par 21
 * (ADR-007). La panne correspondante (P2) sera injectee en aval de l'API, au jalon J8.
 */
public record ValorisationRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal tauxTva,
        @NotNull Long fournisseurId,
        @NotNull Long sousFamilleId,
        @NotEmpty @Valid List<PrixLigne> prix
) {

    /** Prix unitaire HT d'une ligne existante de la demande. */
    public record PrixLigne(
            @NotNull Long ligneId,
            @NotNull @PositiveOrZero BigDecimal prixUnitaireHt
    ) {
        /** Conversion vers le DTO attendu par la couche service (seuls id et prix servent). */
        public LigneDemandeDto versDtoService() {
            return new LigneDemandeDto(ligneId, null, null, null, null, null, prixUnitaireHt);
        }
    }
}
