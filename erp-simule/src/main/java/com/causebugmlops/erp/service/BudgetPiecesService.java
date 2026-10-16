package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.budget.BudgetPieces;
import com.causebugmlops.erp.exception.BudgetInsuffisantException;
import com.causebugmlops.erp.exception.EquationBudgetaireException;
import com.causebugmlops.erp.repository.BudgetPiecesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetPiecesService {

    private static final BigDecimal TOLERANCE_EQUATION = new BigDecimal("0.02");

    private final BudgetPiecesRepository budgetPiecesRepository;

    @Transactional
    public void consommerBudgetPieces(BigDecimal montant, UUID demandeId, String exercice) {
        BudgetPieces bp = budgetPiecesRepository.findByExerciceForUpdate(exercice)
                .orElseThrow(() -> new IllegalArgumentException("Budget pieces introuvable pour l'exercice: " + exercice));

        if (!bp.hasEnoughBudget(montant)) {
            BigDecimal budgetRestant = bp.getBudgetRestant() != null ? bp.getBudgetRestant() : BigDecimal.ZERO;
            BigDecimal montantManquant = montant.subtract(budgetRestant);
            throw new BudgetInsuffisantException(
                    String.format("Budget pieces insuffisant pour l'exercice %s (demandeId: %s)", exercice, demandeId),
                    montantManquant);
        }

        bp.deductBudget(montant);
        BudgetPieces saved = budgetPiecesRepository.save(bp);

        validerEquationBudgetaire(saved, demandeId, exercice);
    }

    @Transactional
    public void restituterBudgetPieces(BigDecimal montant, UUID demandeId, String exercice) {
        BudgetPieces bp = budgetPiecesRepository.findByExerciceForUpdate(exercice)
                .orElseThrow(() -> new IllegalArgumentException("Budget pieces introuvable pour l'exercice: " + exercice));

        bp.addBudget(montant);
        BudgetPieces saved = budgetPiecesRepository.save(bp);

        validerEquationBudgetaire(saved, demandeId, exercice);
    }

    private void validerEquationBudgetaire(BudgetPieces bp, UUID demandeId, String exercice) {
        BigDecimal somme = bp.getBudgetEngage().add(bp.getBudgetRestant());
        BigDecimal ecart = bp.getBudgetInitial().subtract(somme).abs();

        if (ecart.compareTo(TOLERANCE_EQUATION) > 0) {
            log.error("Rupture de l'equation budgetaire pieces pour l'exercice {} (demandeId: {}): initial={}, engage={}, restant={}, ecart={}",
                    exercice, demandeId, bp.getBudgetInitial(), bp.getBudgetEngage(), bp.getBudgetRestant(), ecart);
            throw new EquationBudgetaireException(exercice, bp.getBudgetInitial(), bp.getBudgetEngage(), bp.getBudgetRestant());
        }
    }
}
