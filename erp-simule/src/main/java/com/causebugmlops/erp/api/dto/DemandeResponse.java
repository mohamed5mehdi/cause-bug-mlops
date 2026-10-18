package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.common.UrgenceDemande;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.demande.LigneDemande;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vue publique d'une demande d'achat, renvoyee en JSON.
 * <p>
 * L'entite JPA n'est jamais exposee directement : ses relations sont chargees a
 * la demande (lazy) et la session est fermee hors de la couche service
 * ({@code open-in-view: false}). Ce record ne contient que des valeurs simples,
 * copiees pendant que les donnees sont chargees.
 *
 * @param id      identifiant de la demande, qui sert aussi de businessCorrelationId
 * @param version numero de version optimiste (@Version), utile pour detecter les conflits
 */
public record DemandeResponse(
        UUID id,
        TypeDemande typeDemande,
        StatutDemande statut,
        UrgenceDemande urgence,
        String justification,
        Long demandeurId,
        BigDecimal montantHt,
        BigDecimal tauxTva,
        BigDecimal montantTtc,
        Long version,
        List<Ligne> lignes
) {

    /** Une ligne telle qu'elle est enregistree. */
    public record Ligne(
            Long id,
            Long articleId,
            String designationLibre,
            Integer quantite,
            BigDecimal prixUnitaireHt
    ) {
        static Ligne depuis(LigneDemande l) {
            return new Ligne(
                    l.getId(),
                    l.getArticle() != null ? l.getArticle().getId() : null,
                    l.getDesignationLibre(),
                    l.getQuantite(),
                    l.getPrixUnitaireHt());
        }
    }

    /**
     * Copie les valeurs de l'entite dans la vue publique.
     * Precondition : demandeur, lignes et articles deja charges
     * (voir DemandeAchatInterneRepository#findDetailById).
     */
    public static DemandeResponse depuis(DemandeAchatInterne d) {
        return new DemandeResponse(
                d.getId(),
                d.getTypeDemande(),
                d.getStatut(),
                d.getUrgence(),
                d.getJustification(),
                d.getDemandeur().getId(),
                d.getMontantHt(),
                d.getTauxTva(),
                d.getMontantTtc(),
                d.getVersion(),
                d.getLignes().stream().map(Ligne::depuis).toList());
    }
}
