package com.causebugmlops.erp.domain.common;

/**
 * Neuf roles metier identifies dans le domaine source ; DIRECTEUR_SITE et les deux
 * variantes MAGASINIER (emetteur/destination) propres au flux Transfert Inter-sites
 * sont hors perimetre (cf. decision de scope Phase 1).
 */
public enum RoleUtilisateur {
    DEMANDEUR,
    MANAGER_N1,
    TECHNICIEN,
    ACHETEUR,
    AMG,
    DAF,
    DG,
    MAGASINIER,
    COMPTABLE
}
