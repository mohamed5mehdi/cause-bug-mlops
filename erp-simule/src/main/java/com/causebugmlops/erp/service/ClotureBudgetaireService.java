package com.causebugmlops.erp.service;

import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.repository.SousFamilleBudgetaireRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Traitement de cloture budgetaire simule, utilise par la panne P1 (ADR-018) : il pose le
 * verrou PESSIMISTIC_WRITE de l'enveloppe et le garde pendant toute sa duree, comme un
 * traitement de fin de periode trop long. Il ne modifie aucune donnee.
 * <p>
 * Dans le paquet service pour etre trace comme les autres operations (ADR-010) ; il
 * s'execute sur son propre fil, donc sans businessCorrelationId.
 */
@Service
@RequiredArgsConstructor
public class ClotureBudgetaireService {

    private final SousFamilleBudgetaireRepository sousFamilleRepository;

    @Transactional
    public void cloturerEnveloppe(Long sousFamilleId, Duration duree, CountDownLatch verrouPose) {
        sousFamilleRepository.findByIdForUpdate(sousFamilleId)
                .orElseThrow(() -> new RessourceIntrouvableException("Sous-famille", sousFamilleId));
        verrouPose.countDown();
        try {
            Thread.sleep(duree.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
