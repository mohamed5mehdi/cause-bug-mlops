package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.commande.LignePurchaseOrder;
import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.StatutPurchaseOrder;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
import com.causebugmlops.erp.repository.PoNumeroCompteurRepository;
import com.causebugmlops.erp.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoNumeroCompteurRepository poNumeroCompteurRepository;

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
