package com.causebugmlops.erp.domain.common;

/**
 * Decision enregistree dans la piste d'audit (historique_validation).
 * Liste fermee : une decision inconnue ne compile pas (correction E5, ADR-009).
 */
public enum DecisionValidation {
    VALIDE,
    REJETE,
    AJUSTEMENT_DEMANDE,
    /** L'acheteur soumet une demande d'ajustement budgetaire. */
    SOUMISSION_AJUSTEMENT,
    /** L'acheteur confirme apres la decision DAF ou DG. */
    CONFIRMATION,
    /** L'AMG finalise l'ajustement. */
    FINALISATION
}
