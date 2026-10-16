package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.common.DecisionValidation;
import com.causebugmlops.erp.domain.common.EtapeValidation;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.demande.HistoriqueValidation;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.repository.HistoriqueValidationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HistoriqueValidationService {

    private final HistoriqueValidationRepository repository;

    @Transactional
    public void enregistrerDecision(DemandeAchatInterne demande, Utilisateur acteur, EtapeValidation etape, DecisionValidation decision, String commentaire) {
        HistoriqueValidation h = HistoriqueValidation.builder()
                .demandeAchat(demande)
                .etape(etape)
                .acteur(acteur)
                .decision(decision)
                .commentaire(commentaire)
                .businessCorrelationId(demande.getId())
                .build();
        repository.save(h);
    }

    @Transactional
    public void enregistrerDecision(DemandeAchatInterne demande, Utilisateur acteur, EtapeValidation etape, String decisionStr, String commentaire) {
        DecisionValidation decision;
        try {
            decision = DecisionValidation.valueOf(decisionStr);
        } catch (Exception e) {
            decision = DecisionValidation.VALIDE;
        }
        enregistrerDecision(demande, acteur, etape, decision, commentaire);
    }
}
