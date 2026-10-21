package com.causebugmlops.erp.api;

import com.causebugmlops.erp.api.dto.AjustementRequest;
import com.causebugmlops.erp.api.dto.AjustementResponse;
import com.causebugmlops.erp.api.dto.DecisionRequest;
import com.causebugmlops.erp.api.dto.JustificationRequest;
import com.causebugmlops.erp.domain.ajustement.DemandeAjustement;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.DemandeAjustementRepository;
import com.causebugmlops.erp.repository.UtilisateurRepository;
import com.causebugmlops.erp.service.DemandeAjustementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Point d'entree HTTP de l'ajustement budgetaire (CU-03), rattache a sa demande (ADR-019).
 * <p>
 * Les routes sont sous {@code /api/demandes/{id}/...} : l'intercepteur de correlation lit
 * la variable {@code id}, donc chaque trace de l'ajustement porte le businessCorrelationId
 * de la demande et apparait dans le bon dossier de diagnostic.
 * <p>
 * Aucune regle metier ici. En particulier, le defaut T1a de
 * {@link DemandeAjustementService#confirmerAcheteur} (demande passee EN_VALORISATION sans
 * valorisation) est conserve volontairement : c'est la panne P3 (ADR-002).
 */
@RestController
@RequestMapping("/api/demandes/{id}/ajustements")
@RequiredArgsConstructor
public class AjustementController {

    private final DemandeAjustementService ajustementService;
    private final DemandeAjustementRepository ajustementRepository;
    private final DemandeAchatInterneRepository demandeRepository;
    private final UtilisateurRepository utilisateurRepository;

    /** L'Acheteur soumet un ajustement. Reponse : 201 Created + en-tete Location. */
    @PostMapping
    public ResponseEntity<AjustementResponse> soumettre(
            @PathVariable UUID id,
            @RequestHeader(DemandeAchatController.EN_TETE_UTILISATEUR) Long utilisateurId,
            @Valid @RequestBody AjustementRequest requete) {
        if (!demandeRepository.existsById(id)) {
            throw new RessourceIntrouvableException("Demande", id);
        }
        DemandeAjustement cree = ajustementService.soumettreAjustement(id, requete.type(), requete.montant(),
                requete.sourceSousFamilleId(), requete.cibleSousFamilleId(), requete.familleId(),
                trouverUtilisateur(utilisateurId), requete.justification());
        URI adresse = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{ajustementId}").buildAndExpand(cree.getId()).toUri();
        return ResponseEntity.created(adresse).body(AjustementResponse.depuis(cree, id));
    }

    /** EN_ATTENTE_DAF -> EN_ATTENTE_ACHETEUR (valide) ou REJETE. */
    @PostMapping("/{ajustementId}/decision-daf")
    public AjustementResponse deciderDaf(@PathVariable UUID id, @PathVariable Long ajustementId,
                                         @RequestHeader(DemandeAchatController.EN_TETE_UTILISATEUR) Long utilisateurId,
                                         @Valid @RequestBody DecisionRequest decision) {
        verifierAppartenance(id, ajustementId);
        ajustementService.deciderDaf(ajustementId, decision.valider() ? "VALIDE" : "REJETE",
                trouverUtilisateur(utilisateurId), decision.commentaire());
        return lire(id, ajustementId);
    }

    /** EN_ATTENTE_ACHETEUR -> EN_ATTENTE_AMG (la demande passe EN_VALORISATION : defaut T1a). */
    @PostMapping("/{ajustementId}/confirmation-acheteur")
    public AjustementResponse confirmerAcheteur(@PathVariable UUID id, @PathVariable Long ajustementId,
                                                @RequestHeader(DemandeAchatController.EN_TETE_UTILISATEUR) Long utilisateurId,
                                                @Valid @RequestBody JustificationRequest requete) {
        verifierAppartenance(id, ajustementId);
        ajustementService.confirmerAcheteur(ajustementId, requete.justification(), trouverUtilisateur(utilisateurId));
        return lire(id, ajustementId);
    }

    /** EN_ATTENTE_AMG -> VALIDE. */
    @PostMapping("/{ajustementId}/finalisation-amg")
    public AjustementResponse finaliserAmg(@PathVariable UUID id, @PathVariable Long ajustementId,
                                           @RequestHeader(DemandeAchatController.EN_TETE_UTILISATEUR) Long utilisateurId) {
        verifierAppartenance(id, ajustementId);
        ajustementService.finaliserAmg(ajustementId, trouverUtilisateur(utilisateurId));
        return lire(id, ajustementId);
    }

    /** 404 si l'ajustement n'existe pas OU appartient a une autre demande (pas de corrId errone). */
    private void verifierAppartenance(UUID demandeId, Long ajustementId) {
        if (!ajustementRepository.existsByIdAndDemandeInterneId(ajustementId, demandeId)) {
            throw new RessourceIntrouvableException("Ajustement", ajustementId);
        }
    }

    private AjustementResponse lire(UUID demandeId, Long ajustementId) {
        return ajustementRepository.findById(ajustementId)
                .map(a -> AjustementResponse.depuis(a, demandeId))
                .orElseThrow(() -> new RessourceIntrouvableException("Ajustement", ajustementId));
    }

    private Utilisateur trouverUtilisateur(Long id) {
        return utilisateurRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur", id));
    }
}
