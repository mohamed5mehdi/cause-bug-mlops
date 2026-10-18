package com.causebugmlops.erp.exception;

/**
 * Une ressource designee par son identifiant n'existe pas (demande, utilisateur,
 * fournisseur, sous-famille, article). Traduite en HTTP 404 par l'API.
 * <p>
 * Remplace les {@code IllegalArgumentException("... introuvable")} generiques : une
 * exception generique restante signale desormais un defaut, pas un refus metier
 * (cf. ADR-008).
 */
public class RessourceIntrouvableException extends RuntimeException {

    private final String ressource;
    private final String identifiant;

    public RessourceIntrouvableException(String ressource, Object identifiant) {
        super(ressource + " introuvable : " + identifiant);
        this.ressource = ressource;
        this.identifiant = String.valueOf(identifiant);
    }

    public String getRessource() {
        return ressource;
    }

    public String getIdentifiant() {
        return identifiant;
    }
}
