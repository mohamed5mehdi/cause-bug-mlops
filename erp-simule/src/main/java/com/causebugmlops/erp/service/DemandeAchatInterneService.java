package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.common.DecisionValidation;
import com.causebugmlops.erp.domain.common.EtapeValidation;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.common.UrgenceDemande;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.demande.LigneDemande;
import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.dto.LigneDemandeDto;
import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.exception.TransitionStatutInvalideException;
import com.causebugmlops.erp.repository.ArticleRepository;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.FournisseurRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DemandeAchatInterneService {

    private final DemandeAchatInterneRepository demandeRepository;
    private final FournisseurRepository fournisseurRepository;
    private final ArticleRepository articleRepository;
    private final BudgetGovernanceService budgetService;
    private final BudgetPiecesService budgetPiecesService;
    private final StockGovernanceService stockGovernanceService;
    private final PurchaseOrderService purchaseOrderService;
    private final HistoriqueValidationService historiqueValidationService;

    @Transactional
    public DemandeAchatInterne creerBrouillon(Utilisateur demandeur, TypeDemande typeDemande, UrgenceDemande urgence, String justification, List<LigneDemandeDto> lignesDto) {
        DemandeAchatInterne demande = DemandeAchatInterne.builder()
                .demandeur(demandeur)
                .typeDemande(typeDemande)
                .urgence(urgence != null ? urgence : UrgenceDemande.NORMALE)
                .justification(justification)
                .statut(StatutDemande.BROUILLON)
                .build();

        if (lignesDto != null) {
            List<LigneDemande> lignes = new ArrayList<>();
            for (LigneDemandeDto dto : lignesDto) {
                LigneDemande ld = LigneDemande.builder()
                        .demandeAchat(demande)
                        .article(dto.articleId() != null ? articleRepository.findById(dto.articleId())
                                .orElseThrow(() -> new RessourceIntrouvableException("Article", dto.articleId())) : null)
                        .designationLibre(dto.designationLibre())
                        .specification(dto.specification())
                        .quantite(dto.quantite())
                        .justificationLigne(dto.justificationLigne())
                        .prixUnitaireHt(dto.prixUnitaire())
                        .build();
                lignes.add(ld);
            }
            demande.setLignes(lignes);
        }

        return demandeRepository.save(demande);
    }

    @Transactional
    public DemandeAchatInterne soumettre(UUID id) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);
        if (demande.getStatut() != StatutDemande.BROUILLON) {
            throw new TransitionStatutInvalideException("Seule une demande au statut BROUILLON peut etre soumise. Statut actuel: " + demande.getStatut(), demande.getStatut());
        }

        demande.setStatut(StatutDemande.SOUMISE);
        demande.setDateSoumission(Instant.now());

        return demandeRepository.save(demande);
    }

    @Transactional
    public void validerN1(UUID id, boolean valider, Utilisateur valideur, String commentaire) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);
        if (demande.getStatut() != StatutDemande.SOUMISE) {
            throw new TransitionStatutInvalideException("Seule une demande au statut SOUMISE peut etre validee par N+1. Statut actuel: " + demande.getStatut(), demande.getStatut());
        }

        if (valider) {
            demande.setStatut(StatutDemande.VALIDEE_N1);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.N1, DecisionValidation.VALIDE.name(), commentaire);
        } else {
            demande.setStatut(StatutDemande.REJETEE);
            demande.setEtapeRejet(EtapeValidation.N1);
            demande.setMotifRejet(commentaire);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.N1, DecisionValidation.REJETE.name(), commentaire);
        }

        demandeRepository.save(demande);
    }

    @Transactional
    public void validerTechnique(UUID id) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);

        if (demande.getTypeDemande() == TypeDemande.ACHAT_PIECE) {
            throw new IllegalStateException("validerTechnique ne peut pas etre appelee pour un ACHAT_PIECE");
        }

        demande.setStatut(StatutDemande.VALIDEE_TECHNIQUE);
        demandeRepository.save(demande);
    }

    @Transactional
    public void valoriser(UUID id, List<LigneDemandeDto> lignesPrix, BigDecimal tauxTva, Long fournisseurId, Long sousFamilleId) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);

        if (demande.getStatut() != StatutDemande.VALIDEE_TECHNIQUE) {
            throw new TransitionStatutInvalideException("Seule une demande au statut VALIDEE_TECHNIQUE peut etre valorisee. Statut actuel: " + demande.getStatut(), demande.getStatut());
        }

        Fournisseur fournisseur = fournisseurRepository.findById(fournisseurId)
                .orElseThrow(() -> new RessourceIntrouvableException("Fournisseur", fournisseurId));

        if (lignesPrix != null && demande.getLignes() != null) {
            for (LigneDemandeDto dto : lignesPrix) {
                if (dto.id() != null) {
                    for (LigneDemande ld : demande.getLignes()) {
                        if (dto.id().equals(ld.getId())) {
                            ld.setPrixUnitaireHt(dto.prixUnitaire());
                        }
                    }
                }
            }
        }

        BigDecimal montantHt = BigDecimal.ZERO;
        if (demande.getLignes() != null) {
            for (LigneDemande ld : demande.getLignes()) {
                BigDecimal ligneHt = ld.getMontantLigneHt();
                if (ligneHt != null) {
                    montantHt = montantHt.add(ligneHt);
                }
            }
        }

        BigDecimal multiplicateur = BigDecimal.ONE.add(tauxTva != null ? tauxTva : BigDecimal.ZERO);
        BigDecimal montantTtc = montantHt.multiply(multiplicateur);

        demande.setMontantHt(montantHt);
        demande.setTauxTva(tauxTva);
        demande.setMontantTtc(montantTtc);
        demande.setFournisseurSelectionne(fournisseur);

        if (demande.getTypeDemande() == TypeDemande.ACHAT_INTERNE) {
            // Appel sans capture d'exception pour que BudgetInsuffisantException remonte à l'appelant
            budgetService.verifierEtEngager(sousFamilleId, montantTtc);
            demande.setStatut(StatutDemande.EN_VALORISATION);
        } else if (demande.getTypeDemande() == TypeDemande.ACHAT_PIECE) {
            Instant dateSoumission = demande.getDateSoumission() != null ? demande.getDateSoumission() : Instant.now();
            String exercice = String.valueOf(dateSoumission.atZone(ZoneOffset.UTC).getYear());
            budgetPiecesService.consommerBudgetPieces(montantTtc, demande.getId(), exercice);
            demande.setStatut(StatutDemande.APPROUVEE);
        }

        demandeRepository.save(demande);
    }

    @Transactional
    public void verifierStock(UUID id) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);

        if (demande.getStatut() != StatutDemande.SOUMISE && demande.getStatut() != StatutDemande.VALIDEE_N1) {
            throw new IllegalStateException("Le statut doit etre SOUMISE ou VALIDEE_N1 pour verifierStock. Statut actuel: " + demande.getStatut());
        }

        if (demande.getTypeDemande() != TypeDemande.ACHAT_PIECE) {
            throw new IllegalStateException("verifierStock s'applique exclusivement au flux ACHAT_PIECE");
        }

        demande.setStatut(StatutDemande.EN_VERIFICATION_STOCK);

        boolean stockDisponible = true;
        if (demande.getLignes() != null) {
            for (LigneDemande ld : demande.getLignes()) {
                if (ld.getArticle() != null) {
                    Integer stockActuel = ld.getArticle().getQuantiteEnStock();
                    if (stockActuel == null || stockActuel < ld.getQuantite()) {
                        stockDisponible = false;
                        break;
                    }
                }
            }
        }

        if (stockDisponible && demande.getLignes() != null && !demande.getLignes().isEmpty()) {
            for (LigneDemande ld : demande.getLignes()) {
                if (ld.getArticle() != null) {
                    stockGovernanceService.verifierStock(ld.getArticle().getId(), ld.getQuantite());
                }
            }
            demande.setStatut(StatutDemande.LIVREE_DIRECTE);
        } else {
            // En cas de rupture, renvoie la demande vers l'étape de valorisation acheteur :
            // le prix d'une pièce n'est jamais connu à l'avance (ni sur Article ni ailleurs,
            // confirmé sur le dépôt réel : saisie manuelle par l'Acheteur via valoriser()).
            // L'appel à purchaseOrderService.genererDepuisDemande n'a lieu qu'après un appel
            // explicite à valoriser() côté Acheteur, qui posera APPROUVEE.
            demande.setStatut(StatutDemande.VALIDEE_TECHNIQUE);
        }

        demandeRepository.save(demande);
    }

    @Transactional
    public void validerAmg(UUID id, boolean valider, Utilisateur valideur, String commentaire) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);

        if (demande.getStatut() != StatutDemande.EN_VALORISATION) {
            throw new TransitionStatutInvalideException("Le statut doit etre EN_VALORISATION pour validerAmg. Statut actuel: " + demande.getStatut(), demande.getStatut());
        }

        if (valider) {
            demande.setStatut(StatutDemande.VALIDEE_AMG);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.AMG, DecisionValidation.VALIDE.name(), commentaire);
        } else {
            demande.setStatut(StatutDemande.REJETEE);
            demande.setEtapeRejet(EtapeValidation.AMG);
            demande.setMotifRejet(commentaire);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.AMG, DecisionValidation.REJETE.name(), commentaire);
        }

        demandeRepository.save(demande);
    }

    @Transactional
    public void validerDaf(UUID id, boolean valider, Utilisateur valideur, String commentaire) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);
        if (demande.getStatut() != StatutDemande.VALIDEE_AMG) {
            throw new TransitionStatutInvalideException("Le statut doit etre VALIDEE_AMG pour validerDaf. Statut actuel: " + demande.getStatut(), demande.getStatut());
        }

        if (valider) {
            demande.setStatut(StatutDemande.VALIDEE_DAF);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.DAF, DecisionValidation.VALIDE.name(), commentaire);
        } else {
            demande.setStatut(StatutDemande.REJETEE);
            demande.setEtapeRejet(EtapeValidation.DAF);
            demande.setMotifRejet(commentaire);
            historiqueValidationService.enregistrerDecision(demande, valideur, EtapeValidation.DAF, DecisionValidation.REJETE.name(), commentaire);
        }

        demandeRepository.save(demande);
    }

    @Transactional
    public void validerDg(UUID demandeId) {
        DemandeAchatInterne demande = getDemandeOrThrow(demandeId);

        if (demande.getStatut() != StatutDemande.VALIDEE_DAF) {
            throw new TransitionStatutInvalideException("Le statut doit etre VALIDEE_DAF pour validerDg. Actuel: " + demande.getStatut(), demande.getStatut());
        }

        demande.setStatut(StatutDemande.VALIDEE_DG);
        demandeRepository.save(demande);
    }

    @Transactional
    public void rejeter(UUID id, EtapeValidation etape, String motif, Utilisateur acteur) {
        DemandeAchatInterne demande = getDemandeOrThrow(id);

        demande.setStatut(StatutDemande.REJETEE);
        demande.setEtapeRejet(etape);
        demande.setMotifRejet(motif);

        historiqueValidationService.enregistrerDecision(demande, acteur, etape, DecisionValidation.REJETE.name(), motif);

        demandeRepository.save(demande);
    }

    private DemandeAchatInterne getDemandeOrThrow(UUID id) {
        return demandeRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Demande", id));
    }
}
