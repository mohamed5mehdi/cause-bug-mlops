package com.causebugmlops.erp.api.dto;

import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import com.causebugmlops.erp.domain.common.StatutPurchaseOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue publique d'un Purchase Order genere a partir d'une demande validee par le DG.
 *
 * @param numeroPo     numero unique au format PO-AAAAMMJJ-NNNN (fonction SQL erp.po_numero_compteur)
 * @param montantTotal montant TTC repris de la valorisation
 */
public record PurchaseOrderResponse(
        Long id,
        String numeroPo,
        StatutPurchaseOrder statut,
        BigDecimal montantTotal,
        Instant dateGeneration,
        UUID demandeId
) {
    /** Copie des valeurs simples ; demandeId est fourni par l'appelant (relation lazy). */
    public static PurchaseOrderResponse depuis(PurchaseOrder po, UUID demandeId) {
        return new PurchaseOrderResponse(po.getId(), po.getNumeroPo(), po.getStatut(),
                po.getMontantTotal(), po.getDateGeneration(), demandeId);
    }
}
