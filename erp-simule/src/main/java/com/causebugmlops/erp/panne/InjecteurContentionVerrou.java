package com.causebugmlops.erp.panne;

import com.causebugmlops.erp.service.ClotureBudgetaireService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Panne P1 (ADR-018) : lance en parallele une cloture budgetaire qui retient le verrou de
 * l'enveloppe plus longtemps que le delai de verrou des repositories (3 s). La requete
 * metier qui demande ensuite ce verrou echoue en delai depasse, comme en production
 * lorsqu'un traitement concurrent monopolise une ligne.
 */
@Component
public class InjecteurContentionVerrou {

    private static final Logger LOG = LoggerFactory.getLogger(InjecteurContentionVerrou.class);

    /** Superieure au delai de verrou de 3 s pour garantir le depassement. */
    static final Duration DUREE_CLOTURE = Duration.ofSeconds(6);
    private static final long ATTENTE_POSE_VERROU_MS = 2000;

    private final ClotureBudgetaireService cloture;
    private final ExecutorService executeur = Executors.newSingleThreadExecutor(r -> {
        Thread fil = new Thread(r, "cloture-budgetaire");
        fil.setDaemon(true);
        return fil;
    });

    public InjecteurContentionVerrou(ClotureBudgetaireService cloture) {
        this.cloture = cloture;
    }

    /** Pose le verrou concurrent et rend la main une fois qu'il est effectivement tenu. */
    public void retenirVerrou(Long sousFamilleId) {
        CountDownLatch verrouPose = new CountDownLatch(1);
        executeur.submit(() -> cloture.cloturerEnveloppe(sousFamilleId, DUREE_CLOTURE, verrouPose));
        try {
            if (!verrouPose.await(ATTENTE_POSE_VERROU_MS, TimeUnit.MILLISECONDS)) {
                LOG.warn("Panne P1 : la cloture n'a pas pose le verrou de la sous-famille {} a temps", sousFamilleId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void arreter() {
        executeur.shutdownNow();
    }
}
