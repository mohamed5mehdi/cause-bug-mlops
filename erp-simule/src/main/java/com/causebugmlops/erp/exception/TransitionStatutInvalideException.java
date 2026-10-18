package com.causebugmlops.erp.exception;

import com.causebugmlops.erp.domain.common.StatutDemande;

/**
 * Action du workflow demandee alors que la demande n'est pas au statut attendu
 * (ex. valider DAF une demande encore EN_VALORISATION). Traduite en HTTP 409.
 * <p>
 * Reservee aux gardes de statut de la machine a etats. Les incoherences internes
 * (montant non valorise, fournisseur absent a la generation du PO...) restent des
 * {@link IllegalStateException} : ce sont des defauts, rendus en 500 (cf. ADR-008).
 * <p>
 * Herite d'{@link IllegalStateException} : le contrat existant des appelants (dont
 * ConstatsBlocCVerificationTest, constat E) reste valide ; seul le gestionnaire HTTP
 * distingue ce sous-type, le plus specifique l'emportant sur le cas generique.
 */
public class TransitionStatutInvalideException extends IllegalStateException {

    private final StatutDemande statutActuel;

    public TransitionStatutInvalideException(String message, StatutDemande statutActuel) {
        super(message);
        this.statutActuel = statutActuel;
    }

    public StatutDemande getStatutActuel() {
        return statutActuel;
    }
}
