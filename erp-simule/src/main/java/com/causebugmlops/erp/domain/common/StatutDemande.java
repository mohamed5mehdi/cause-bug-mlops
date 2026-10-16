package com.causebugmlops.erp.domain.common;

/**
 * Machine a etats de {@code DemandeAchatInterne}. Le bypass N+1 (rupture SAV urgente)
 * n'est pas modelise ici : c'est un attribut booleen sur l'entite, pas une branche
 * d'etats, pour eviter une explosion combinatoire.
 */
public enum StatutDemande {
    BROUILLON,
    SOUMISE,
    VALIDEE_N1,
    VALIDEE_TECHNIQUE,
    EN_VALORISATION,
    EN_VERIFICATION_STOCK,
    AJUSTEMENT_SOUS_FAMILLE_EN_COURS,
    AJUSTEMENT_FAMILLE_EN_COURS,
    VALIDEE_AMG,
    VALIDEE_DAF,
    VALIDEE_DG,
    APPROUVEE,
    PO_GENERE,
    LIVREE_DIRECTE,
    CLOTUREE,
    REJETEE
}
