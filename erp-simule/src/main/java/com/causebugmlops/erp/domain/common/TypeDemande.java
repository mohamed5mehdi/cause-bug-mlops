package com.causebugmlops.erp.domain.common;

/**
 * Discriminant portee par {@code DemandeAchatInterne}, qui centralise volontairement
 * les deux flux (cf. dictionnaire de donnees du domaine source).
 */
public enum TypeDemande {
    ACHAT_INTERNE,
    ACHAT_PIECE
}
