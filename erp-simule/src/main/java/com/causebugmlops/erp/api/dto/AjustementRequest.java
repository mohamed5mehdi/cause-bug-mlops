package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.domain.common.TypeAjustement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Demande d'ajustement budgetaire soumise par l'Acheteur (CU-03).
 * <p>
 * SOUS_FAMILLE : transfert de {@code montant} de la sous-famille source vers la cible,
 * deduit des la soumission. FAMILLE : abondement de la famille cible apres decision du DG.
 *
 * @param justification obligatoire, 1000 caracteres max (colonne justification_acheteur)
 */
public record AjustementRequest(
        @NotNull TypeAjustement type,
        @NotNull @Positive BigDecimal montant,
        Long sourceSousFamilleId,
        Long cibleSousFamilleId,
        Long familleId,
        @NotBlank @Size(max = 1000) String justification
) {}
