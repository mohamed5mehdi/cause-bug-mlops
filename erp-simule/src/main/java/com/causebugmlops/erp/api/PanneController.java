package com.causebugmlops.erp.api;

import com.causebugmlops.erp.panne.CodePanne;
import com.causebugmlops.erp.panne.InterrupteurPannes;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Arme et desarme les pannes de demonstration (ADR-018).
 * <p>
 * N'existe que sous le profil {@code pannes} : sans lui, la route renvoie 404. Elle n'est
 * pas authentifiee, ce qui est acceptable pour un simulateur local, jamais pour un
 * systeme expose. Un code inconnu est refuse en 400.
 */
@Profile("pannes")
@RestController
@RequestMapping("/api/pannes")
public class PanneController {

    /** Etat d'une panne, avec le composant attendu (verite terrain du diagnostic). */
    public record EtatPanne(String code, String description, String composantAttendu, boolean armee) {
    }

    private final InterrupteurPannes interrupteur;

    public PanneController(InterrupteurPannes interrupteur) {
        this.interrupteur = interrupteur;
    }

    @GetMapping
    public List<EtatPanne> etat() {
        return Arrays.stream(CodePanne.values())
                .map(p -> new EtatPanne(p.name(), p.description(), p.composantAttendu(), interrupteur.estArmee(p)))
                .toList();
    }

    @PostMapping("/{code}")
    public List<EtatPanne> armer(@PathVariable CodePanne code) {
        interrupteur.armer(code);
        return etat();
    }

    @DeleteMapping("/{code}")
    public List<EtatPanne> desarmer(@PathVariable CodePanne code) {
        interrupteur.desarmer(code);
        return etat();
    }
}
