package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.budget.FamilleBudgetaire;
import com.causebugmlops.erp.domain.budget.SousFamilleBudgetaire;
import com.causebugmlops.erp.exception.BudgetInsuffisantException;
import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.repository.FamilleBudgetaireRepository;
import com.causebugmlops.erp.repository.SousFamilleBudgetaireRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class BudgetGovernanceService {

    private final SousFamilleBudgetaireRepository sousFamilleRepository;
    private final FamilleBudgetaireRepository familleRepository;

    @Transactional
    public void verifierEtEngager(Long sousFamilleId, BigDecimal montantTtc) {
        SousFamilleBudgetaire sf = sousFamilleRepository.findByIdForUpdate(sousFamilleId)
                .orElseThrow(() -> new RessourceIntrouvableException("Sous-famille", sousFamilleId));
        
        if (sf.getMontantDisponible().compareTo(montantTtc) < 0) {
            BigDecimal montantManquant = montantTtc.subtract(sf.getMontantDisponible());
            throw new BudgetInsuffisantException("Budget insuffisant", montantManquant);
        }
        
        sf.setMontantEngage(sf.getMontantEngage().add(montantTtc));
        sousFamilleRepository.save(sf);
    }

    @Transactional
    public void ajusterSousFamille(Long sourceId, Long cibleId, BigDecimal montant) {
        Long firstId = Math.min(sourceId, cibleId);
        Long secondId = Math.max(sourceId, cibleId);

        SousFamilleBudgetaire first = sousFamilleRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new IllegalArgumentException("Sous-famille introuvable: " + firstId));
        SousFamilleBudgetaire second = sousFamilleRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new IllegalArgumentException("Sous-famille introuvable: " + secondId));

        SousFamilleBudgetaire source = sourceId.equals(firstId) ? first : second;
        SousFamilleBudgetaire cible = cibleId.equals(firstId) ? first : second;

        if (source.getMontantDisponible().compareTo(montant) < 0) {
            BigDecimal montantManquant = montant.subtract(source.getMontantDisponible());
            throw new BudgetInsuffisantException("Budget insuffisant sur la source", montantManquant);
        }

        source.setMontantAlloue(source.getMontantAlloue().subtract(montant));
        cible.setMontantAlloue(cible.getMontantAlloue().add(montant));

        sousFamilleRepository.save(source);
        sousFamilleRepository.save(cible);
    }

    @Transactional
    public void ajusterFamille(Long familleId, BigDecimal montant) {
        FamilleBudgetaire famille = familleRepository.findByIdForUpdate(familleId)
                .orElseThrow(() -> new IllegalArgumentException("Famille introuvable: " + familleId));

        famille.setMontantAlloue(famille.getMontantAlloue().add(montant));
        familleRepository.save(famille);
    }
}
