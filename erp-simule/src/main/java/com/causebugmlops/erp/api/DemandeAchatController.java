package com.causebugmlops.erp.api;

import com.causebugmlops.erp.api.dto.CreerDemandeRequest;
import com.causebugmlops.erp.api.dto.DemandeResponse;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.UtilisateurRepository;
import com.causebugmlops.erp.service.DemandeAchatInterneService;
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
