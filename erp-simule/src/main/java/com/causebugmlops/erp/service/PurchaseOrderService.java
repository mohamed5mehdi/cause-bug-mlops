package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.commande.LignePurchaseOrder;
import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.StatutPurchaseOrder;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.PoNumeroCompteurRepository;
import com.causebugmlops.erp.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoNumeroCompteurRepository poNumeroCompteurRepository;
    private final DemandeAchatInterneRepository demandeRepository;

    /**
     * Point d'entree pour l'API : charge la demande DANS la transaction de generation.
     * <p>
     * La variante {@link #genererDepuisDemande(DemandeAchatInterne)} attend une entite
     * deja attachee a une session ouverte. Appelee depuis un controleur, l'entite serait
     * detachee : la lecture des lignes echouerait (lazy) et le passage au statut
     * PO_GENERE ne serait pas enregistre. Ici, chargement et generation partagent la
     * meme transaction (l'appel interne est couvert par la transaction de cette methode).
     *
     * @throws IllegalArgumentException si la demande n'existe pas
     */
    @Transactional
    public PurchaseOrder genererDepuisDemande(UUID demandeId) {
        DemandeAchatInterne demande = demandeRepository.findById(demandeId)
                .orElseThrow(() -> new IllegalArgumentException("Demande introuvable: " + demandeId));
        return genererDepuisDemande(demande);
    }

    @Transactional
    public PurchaseOrder genererDepuisDemande(DemandeAchatInterne demande) {
        if (demande.getStatut() != StatutDemande.VALIDEE_DG && demande.getStatut() != StatutDemande.APPROUVEE) {
            throw new IllegalStateException("Statut invalide pour generation PO: " + demande.getStatut());
        }

        Fournisseur fournisseurSelectionne = demande.getFournisseurSelectionne();
        if (fournisseurSelectionne == null) {
            throw new IllegalStateException("Fournisseur doit etre selectionne avant generation");
        }

        if (demande.getLignes() == null || demande.getLignes().isEmpty()) {
            throw new IllegalStateException("Demande sans lignes, génération PO impossible");
        }

        String numeroPo = poNumeroCompteurRepository.getNextPoNumero();
        
        // Lecture directe du montant TTC déjà calculé et persisté à la valorisation (Règle 7)
        BigDecimal montantTtc = demande.getMontantTtc();
        if (montantTtc == null) {
            throw new IllegalStateException("Montant TTC non valorisé sur la demande");
        }

        PurchaseOrder po = PurchaseOrder.builder()
                .demandeAchat(demande)
                .fournisseur(fournisseurSelectionne)
                .numeroPo(numeroPo)
                .statut(StatutPurchaseOrder.DRAFT)
                .montantTotal(montantTtc)
                .dateGeneration(Instant.now())
                .build();

        BigDecimal multiplicateurTtc = BigDecimal.ONE.add(demande.getTauxTva() != null ? demande.getTauxTva() : BigDecimal.ZERO);

        List<LignePurchaseOrder> lignes = demande.getLignes().stream().map(ld -> {
            BigDecimal prixU = ld.getPrixUnitaireHt();
            BigDecimal montantLigneHt = ld.getMontantLigneHt();
            BigDecimal montantLigneTtc = montantLigneHt != null ? montantLigneHt.multiply(multiplicateurTtc) : BigDecimal.ZERO;
            return LignePurchaseOrder.builder()
                    .purchaseOrder(po)
                    .ligneDemande(ld)
                    .quantite(ld.getQuantite())
                    .prixUnitaire(prixU != null ? prixU : BigDecimal.ZERO)
                    .montantLigne(montantLigneTtc)
                    .build();
        }).toList();

        po.setLignes(lignes);
        demande.setStatut(StatutDemande.PO_GENERE);

        return purchaseOrderRepository.save(po);
    }
}
