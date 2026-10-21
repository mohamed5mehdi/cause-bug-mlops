package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.domain.ajustement.DemandeAjustement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue publique d'un ajustement budgetaire. Ne lit que des colonnes simples de l'entite
 * (aucune association paresseuse) : sure hors transaction (open-in-view desactive).
 */
public record AjustementResponse(
        Long id,
        UUID demandeId,
        String type,
        String statut,
        BigDecimal montantDemande,
        Long sourceSousFamilleId,
        Long cibleSousFamilleId,
        Long familleCibleId
) {
    public static AjustementResponse depuis(DemandeAjustement a, UUID demandeId) {
        return new AjustementResponse(a.getId(), demandeId, a.getType().name(), a.getStatut().name(),
                a.getMontantDemande(), a.getSourceSousFamilleId(), a.getCibleSousFamilleId(),
                a.getFamilleCibleId());
    }
}
