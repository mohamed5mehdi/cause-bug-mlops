package com.causebugmlops.erp.panne;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Etat des pannes injectees (ADR-018) : un disjoncteur par panne, tous ouverts au
 * demarrage. Le bean existe toujours (les services le consultent), mais seule la route
 * {@code /api/pannes}, presente uniquement sous le profil {@code pannes}, peut armer.
 * Etat en memoire : un redemarrage desarme tout.
 */
@Component
public class InterrupteurPannes {

    private static final Logger LOG = LoggerFactory.getLogger(InterrupteurPannes.class);

    private final Set<CodePanne> armees = ConcurrentHashMap.newKeySet();

    public void armer(CodePanne panne) {
        if (armees.add(panne)) {
            LOG.warn("Panne {} ARMEE : {} (composant attendu : {})", panne, panne.description(),
                    panne.composantAttendu());
        }
    }

    public void desarmer(CodePanne panne) {
        if (armees.remove(panne)) {
            LOG.warn("Panne {} desarmee", panne);
        }
    }

    public boolean estArmee(CodePanne panne) {
        return armees.contains(panne);
    }

    /** Copie immuable de l'etat courant. */
    public Set<CodePanne> armees() {
        return armees.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(armees));
    }
}
