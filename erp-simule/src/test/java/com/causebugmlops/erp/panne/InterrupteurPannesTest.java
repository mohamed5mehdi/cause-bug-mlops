package com.causebugmlops.erp.panne;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Comportement de l'interrupteur de pannes (ADR-018), sans Spring ni base. */
class InterrupteurPannesTest {

    @Test
    void toutesLesPannesSontDesarmeesAuDemarrage() {
        InterrupteurPannes interrupteur = new InterrupteurPannes();

        for (CodePanne panne : CodePanne.values()) {
            assertThat(interrupteur.estArmee(panne)).as("panne %s", panne).isFalse();
        }
        assertThat(interrupteur.armees()).isEmpty();
    }

    @Test
    void armerPuisDesarmerUnePanneSansToucherAuxAutres() {
        InterrupteurPannes interrupteur = new InterrupteurPannes();

        interrupteur.armer(CodePanne.P2);
        assertThat(interrupteur.estArmee(CodePanne.P2)).isTrue();
        assertThat(interrupteur.estArmee(CodePanne.P1)).isFalse();
        assertThat(interrupteur.armees()).containsExactly(CodePanne.P2);

        interrupteur.desarmer(CodePanne.P2);
        assertThat(interrupteur.armees()).isEmpty();
    }

    @Test
    void armerEtDesarmerSontIdempotents() {
        InterrupteurPannes interrupteur = new InterrupteurPannes();

        interrupteur.armer(CodePanne.P1);
        interrupteur.armer(CodePanne.P1);
        assertThat(interrupteur.armees()).containsExactly(CodePanne.P1);

        interrupteur.desarmer(CodePanne.P1);
        interrupteur.desarmer(CodePanne.P1);
        assertThat(interrupteur.armees()).isEmpty();
    }

    @Test
    void chaquePanneDesigneUnComposantDifferent() {
        assertThat(CodePanne.P1.composantAttendu()).isEqualTo("BASE_DE_DONNEES");
        assertThat(CodePanne.P2.composantAttendu()).isEqualTo("CODE_JAVA");
    }
}
