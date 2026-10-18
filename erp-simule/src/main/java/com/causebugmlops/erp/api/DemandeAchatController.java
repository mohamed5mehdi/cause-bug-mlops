package com.causebugmlops.erp.api;

import com.causebugmlops.erp.api.dto.CreerDemandeRequest;
import com.causebugmlops.erp.api.dto.DecisionRequest;
import com.causebugmlops.erp.api.dto.DemandeResponse;
import com.causebugmlops.erp.api.dto.PurchaseOrderResponse;
import com.causebugmlops.erp.api.dto.ValorisationRequest;
import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.UtilisateurRepository;
import com.causebugmlops.erp.service.DemandeAchatInterneService;
import com.causebugmlops.erp.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Point d'entree HTTP du workflow Achat Interne.
 * <p>
 * Role volontairement limite : recevoir et valider le formulaire, identifier
 * l'acteur, deleguer au service metier, renvoyer une vue publique. Aucune regle
 * metier ici : elles restent dans {@link DemandeAchatInterneService}.
 * <p>
 * Identification de l'acteur : en-tete {@code X-Utilisateur-Id}. Ce n'est PAS une
 * authentification (limite assumee du MVP, documentee dans le README).
 */
@RestController
@RequestMapping("/api/demandes")
@RequiredArgsConstructor
public class DemandeAchatController {

    static final String EN_TETE_UTILISATEUR = "X-Utilisateur-Id";

    private final DemandeAchatInterneService demandeService;
    private final PurchaseOrderService purchaseOrderService;
    private final DemandeAchatInterneRepository demandeRepository;
    private final UtilisateurRepository utilisateurRepository;

    /** Cree une demande en brouillon. Reponse : 201 Created + en-tete Location. */
    @PostMapping
    public ResponseEntity<DemandeResponse> creer(
            @RequestHeader(EN_TETE_UTILISATEUR) Long utilisateurId,
            @Valid @RequestBody CreerDemandeRequest requete) {

        Utilisateur demandeur = trouverUtilisateur(utilisateurId);

        DemandeAchatInterne creee = demandeService.creerBrouillon(
                demandeur,
                TypeDemande.ACHAT_INTERNE,
                requete.urgence(),
                requete.justification(),
                requete.lignes().stream().map(CreerDemandeRequest.Ligne::versDtoService).toList());

        URI adresse = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(creee.getId()).toUri();

        return ResponseEntity.created(adresse).body(lireDetail(creee.getId()));
    }

    /** Relit une demande : statut, montants, lignes. */
    @GetMapping("/{id}")
    public DemandeResponse consulter(@PathVariable UUID id) {
        return lireDetail(id);
    }

    // ------------------------------------------------------------------------
    // Workflow de validation. Chaque endpoint delegue a une methode du service,
    // qui verifie le statut attendu ; la reponse est l'etat relu apres l'action.
    // En-tete X-Utilisateur-Id seulement la ou le service enregistre un acteur.
    // ------------------------------------------------------------------------

    /** BROUILLON -> SOUMISE. */
    @PostMapping("/{id}/soumission")
    public DemandeResponse soumettre(@PathVariable UUID id) {
        demandeService.soumettre(id);
        return lireDetail(id);
    }

    /** SOUMISE -> VALIDEE_N1 (ou REJETEE). */
    @PostMapping("/{id}/validation-n1")
    public DemandeResponse validerN1(@PathVariable UUID id,
                                     @RequestHeader(EN_TETE_UTILISATEUR) Long utilisateurId,
                                     @Valid @RequestBody DecisionRequest decision) {
        demandeService.validerN1(id, decision.valider(), trouverUtilisateur(utilisateurId), decision.commentaire());
        return lireDetail(id);
    }

    /** -> VALIDEE_TECHNIQUE. */
    @PostMapping("/{id}/validation-technique")
    public DemandeResponse validerTechnique(@PathVariable UUID id) {
        demandeService.validerTechnique(id);
        return lireDetail(id);
    }

    /** VALIDEE_TECHNIQUE -> EN_VALORISATION : calcule les montants et engage le budget. */
    @PostMapping("/{id}/valorisation")
    public DemandeResponse valoriser(@PathVariable UUID id,
                                     @Valid @RequestBody ValorisationRequest requete) {
        demandeService.valoriser(id,
                requete.prix().stream().map(ValorisationRequest.PrixLigne::versDtoService).toList(),
                requete.tauxTva(), requete.fournisseurId(), requete.sousFamilleId());
        return lireDetail(id);
    }

    /** EN_VALORISATION -> VALIDEE_AMG (ou REJETEE). */
    @PostMapping("/{id}/validation-amg")
    public DemandeResponse validerAmg(@PathVariable UUID id,
                                      @RequestHeader(EN_TETE_UTILISATEUR) Long utilisateurId,
                                      @Valid @RequestBody DecisionRequest decision) {
        demandeService.validerAmg(id, decision.valider(), trouverUtilisateur(utilisateurId), decision.commentaire());
        return lireDetail(id);
    }

    /** VALIDEE_AMG -> VALIDEE_DAF (ou REJETEE). */
    @PostMapping("/{id}/validation-daf")
    public DemandeResponse validerDaf(@PathVariable UUID id,
                                      @RequestHeader(EN_TETE_UTILISATEUR) Long utilisateurId,
                                      @Valid @RequestBody DecisionRequest decision) {
        demandeService.validerDaf(id, decision.valider(), trouverUtilisateur(utilisateurId), decision.commentaire());
        return lireDetail(id);
    }

    /** VALIDEE_DAF -> VALIDEE_DG. */
    @PostMapping("/{id}/validation-dg")
    public DemandeResponse validerDg(@PathVariable UUID id) {
        demandeService.validerDg(id);
        return lireDetail(id);
    }

    /** VALIDEE_DG -> PO_GENERE. Reponse : 201 Created avec le Purchase Order. */
    @PostMapping("/{id}/purchase-order")
    public ResponseEntity<PurchaseOrderResponse> genererPurchaseOrder(@PathVariable UUID id) {
        PurchaseOrder po = purchaseOrderService.genererDepuisDemande(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(PurchaseOrderResponse.depuis(po, id));
    }

    private DemandeResponse lireDetail(UUID id) {
        return demandeRepository.findDetailById(id)
                .map(DemandeResponse::depuis)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Demande introuvable : " + id));
    }

    private Utilisateur trouverUtilisateur(Long id) {
        return utilisateurRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable : " + id));
    }
}
