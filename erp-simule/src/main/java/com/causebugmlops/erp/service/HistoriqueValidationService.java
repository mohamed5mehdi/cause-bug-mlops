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

/**
 * Ecrit la piste d'audit fonctionnelle des decisions du workflow.
 * <p>
 * La decision est typee ({@link DecisionValidation}) : l'ancienne variante qui
 * recevait un texte et convertissait toute valeur inconnue en VALIDE (defaut E5)
 * est supprimee (ADR-009).
 */
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
}
