package com.causebugmlops.erp.panne;

/**
 * Pannes injectables du simulateur d'achats (ADR-018), desarmees par defaut.
 * <p>
 * Chaque panne cible un composant different, pour evaluer le diagnostic sur les trois
 * categories. La panne P3 (FLUX_METIER) n'apparait pas ici : c'est le defaut reel T1a,
 * conserve volontairement (ADR-002), present en permanence et non commutable.
 */
public enum CodePanne {

    /** Une cloture budgetaire lente retient le verrou de l'enveloppe : delai de verrou depasse. */
    P1("Contention de verrou sur l'enveloppe budgetaire", "BASE_DE_DONNEES"),

    /** Le taux de TVA (fraction 0,20) est traite comme un pourcentage : TTC = HT x 21. */
    P2("Taux de TVA interprete comme un pourcentage", "CODE_JAVA");

    private final String description;
    private final String composantAttendu;

    CodePanne(String description, String composantAttendu) {
        this.description = description;
        this.composantAttendu = composantAttendu;
    }

    public String description() {
        return description;
    }

    /** Composant que le diagnostic devrait designer (verite terrain de l'evaluation J9). */
    public String composantAttendu() {
        return composantAttendu;
    }
}
