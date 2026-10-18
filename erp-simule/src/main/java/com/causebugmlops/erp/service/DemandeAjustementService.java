package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.ajustement.DemandeAjustement;
import com.causebugmlops.erp.domain.common.DecisionValidation;
import com.causebugmlops.erp.domain.common.EtapeValidation;
import com.causebugmlops.erp.domain.common.StatutAjustement;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.TypeAjustement;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.DemandeAjustementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DemandeAjustementService {

    private final DemandeAjustementRepository ajustementRepository;
    private final DemandeAchatInterneRepository demandeRepository;
    private final BudgetGovernanceService budgetService;
    private final HistoriqueValidationService historiqueValidationService;

    @Transactional
    public DemandeAjustement soumettreAjustement(UUID demandeId, TypeAjustement type, BigDecimal montant,
            Long sourceSfId, Long cibleSfId, Long familleId, Utilisateur acheteur, String justification) {
        DemandeAchatInterne demande = demandeRepository.findById(demandeId).orElseThrow();

        // Règle 15 : Garde anti-doublon via repository
        boolean hasActive = ajustementRepository.existsByDemandeInterneIdAndStatutIn(demandeId,
                List.of(StatutAjustement.EN_ATTENTE_DAF, StatutAjustement.EN_ATTENTE_DG,
                        StatutAjustement.EN_ATTENTE_ACHETEUR, StatutAjustement.EN_ATTENTE_AMG));
        if (hasActive) {
            throw new IllegalStateException("Un ajustement actif existe déjà pour cette demande.");
        }

        StatutDemande statutAvant = demande.getStatut();

        if (type == TypeAjustement.SOUS_FAMILLE) {
            demande.setStatut(StatutDemande.AJUSTEMENT_SOUS_FAMILLE_EN_COURS);
            demande.setTypeAjustement(type);
            // Règle 16 : déduction à la soumission pour SOUS_FAMILLE
            budgetService.ajusterSousFamille(sourceSfId, cibleSfId, montant);
        } else {
            demande.setStatut(StatutDemande.AJUSTEMENT_FAMILLE_EN_COURS);
            demande.setTypeAjustement(type);
        }

        DemandeAjustement ajustement = DemandeAjustement.builder()
                .demandeInterne(demande)
                .type(type)
                .statut(type == TypeAjustement.SOUS_FAMILLE ? StatutAjustement.EN_ATTENTE_DAF
                        : StatutAjustement.EN_ATTENTE_DG)
                .sourceSousFamilleId(sourceSfId)
                .cibleSousFamilleId(cibleSfId)
                .familleCibleId(familleId)
                .montantDemande(montant)
                .justificationAcheteur(justification)
                .statutDemandeInterneAvantAjustement(statutAvant)
                .acheteur(acheteur)
                .dateCreation(Instant.now())
                .build();

        demandeRepository.save(demande);
        DemandeAjustement savedAjustement = ajustementRepository.save(ajustement);

        historiqueValidationService.enregistrerDecision(demande, acheteur, EtapeValidation.ACHETEUR,
                DecisionValidation.SOUMISSION_AJUSTEMENT, justification);

        return savedAjustement;
    }

    @Transactional
    public void deciderDaf(Long ajustementId, String decision, Utilisateur valideur, String justification) {
        decider(ajustementId, decision, valideur, justification, StatutAjustement.EN_ATTENTE_DAF);
    }

    @Transactional
    public void deciderDg(Long ajustementId, String decision, Utilisateur valideur, String justification) {
        decider(ajustementId, decision, valideur, justification, StatutAjustement.EN_ATTENTE_DG);
    }

    private void decider(Long ajustementId, String decision, Utilisateur valideur, String justification,
            StatutAjustement expectedStatut) {
        DemandeAjustement ajustement = ajustementRepository.findById(ajustementId).orElseThrow();
        if (ajustement.getStatut() != expectedStatut) {
            throw new IllegalStateException("Statut inattendu: " + ajustement.getStatut());
        }

        ajustement.setValideur(valideur);
        ajustement.setJustificationValideur(justification);
        ajustement.setDateDecision(Instant.now());

        DemandeAchatInterne demande = ajustement.getDemandeInterne();

        if ("VALIDE".equals(decision)) {
            // Règle 17 : Injection budget Famille si type FAMILLE avant restauration du
            // statut
            if (ajustement.getType() == TypeAjustement.FAMILLE) {
                budgetService.ajusterFamille(ajustement.getFamilleCibleId(), ajustement.getMontantDemande());
            }
            ajustement.setStatut(StatutAjustement.EN_ATTENTE_ACHETEUR);
            demande.setStatut(ajustement.getStatutDemandeInterneAvantAjustement());
        } else if ("REJETE".equals(decision)) {
            // Règle 18
            ajustement.setStatut(StatutAjustement.REJETE);
            demande.setStatut(StatutDemande.BROUILLON);
            if (ajustement.getType() == TypeAjustement.SOUS_FAMILLE) {
                // Restaure le budget
                budgetService.ajusterSousFamille(ajustement.getCibleSousFamilleId(),
                        ajustement.getSourceSousFamilleId(), ajustement.getMontantDemande());
            }
        } else {
            throw new IllegalArgumentException("Decision invalide: " + decision);
        }

        ajustementRepository.save(ajustement);
        demandeRepository.save(demande);

        EtapeValidation etape = expectedStatut == StatutAjustement.EN_ATTENTE_DAF ? EtapeValidation.DAF
                : EtapeValidation.DG;
        // decision vaut ici "VALIDE" ou "REJETE" : toute autre valeur a ete refusee plus haut.
        historiqueValidationService.enregistrerDecision(demande, valideur, etape, DecisionValidation.valueOf(decision), justification);
    }

    @Transactional
    public void confirmerAcheteur(Long ajustementId, String justification, Utilisateur acheteur) {
        DemandeAjustement ajustement = ajustementRepository.findById(ajustementId).orElseThrow();
        if (ajustement.getStatut() != StatutAjustement.EN_ATTENTE_ACHETEUR) {
            throw new IllegalStateException("Statut inattendu: " + ajustement.getStatut());
        }

        ajustement.setJustificationAcheteur(justification);
        // Règle 19
        ajustement.setStatut(StatutAjustement.EN_ATTENTE_AMG);
        DemandeAchatInterne demande = ajustement.getDemandeInterne();
        demande.setStatut(StatutDemande.EN_VALORISATION);

        ajustementRepository.save(ajustement);
        demandeRepository.save(demande);

        Utilisateur acteur = acheteur != null ? acheteur : ajustement.getAcheteur();
        historiqueValidationService.enregistrerDecision(demande, acteur, EtapeValidation.ACHETEUR, DecisionValidation.CONFIRMATION,
                justification);
    }

    @Transactional
    public void finaliserAmg(Long ajustementId, Utilisateur validateurAmg) {
        DemandeAjustement ajustement = ajustementRepository.findById(ajustementId).orElseThrow();
        if (ajustement.getStatut() != StatutAjustement.EN_ATTENTE_AMG) {
            throw new IllegalStateException("Statut inattendu: " + ajustement.getStatut());
        }

        // Règle 20
        ajustement.setStatut(StatutAjustement.VALIDE);
        ajustementRepository.save(ajustement);

        DemandeAchatInterne demande = ajustement.getDemandeInterne();
        historiqueValidationService.enregistrerDecision(demande, validateurAmg, EtapeValidation.AMG, DecisionValidation.FINALISATION,
                "Ajustement budgétaire finalisé");
    }
}
