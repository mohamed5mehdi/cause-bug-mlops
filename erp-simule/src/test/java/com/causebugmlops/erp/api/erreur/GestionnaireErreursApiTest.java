package com.causebugmlops.erp.api.erreur;

import com.causebugmlops.erp.api.tracabilite.FiltreRequestId;
import com.causebugmlops.erp.api.tracabilite.IntercepteurCorrelationMetier;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.exception.BudgetInsuffisantException;
import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.exception.TransitionStatutInvalideException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifie la correspondance exception -> reponse HTTP (ADR-008) avec un controleur
 * de test qui leve chaque exception : pas de base de donnees, pas de contexte Spring
 * complet. Le filtre et l'intercepteur reels sont branches.
 */
class GestionnaireErreursApiTest {

    private static final String ID = "3f2a9c1e-0b7d-4c5e-9a1f-2b3c4d5e6f70";
    private static final String REQUEST_ID = "test-req-1";

    private MockMvc mockMvc;

    @BeforeEach
    void preparer() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ControleurDeTest())
                .setControllerAdvice(new GestionnaireErreursApi())
                .addFilters(new FiltreRequestId())
                .addInterceptors(new IntercepteurCorrelationMetier())
                .build();
    }

    @Test
    void ressourceIntrouvable_404AvecIdentifiants() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/introuvable", ID).header(FiltreRequestId.EN_TETE, REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(FiltreRequestId.EN_TETE, REQUEST_ID))
                .andExpect(jsonPath("$.code").value("RESSOURCE_INTROUVABLE"))
                .andExpect(jsonPath("$.ressource").value("Demande"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.businessCorrelationId").value(ID));
    }

    @Test
    void actionHorsSequence_409AvecStatutActuel() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/hors-sequence", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_STATUT_INVALIDE"))
                .andExpect(jsonPath("$.statutActuel").value("SOUMISE"))
                .andExpect(jsonPath("$.businessCorrelationId").value(ID));
    }

    @Test
    void budgetInsuffisant_422AvecMontantManquant() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/budget", ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUDGET_INSUFFISANT"))
                .andExpect(jsonPath("$.montantManquant").value(536));
    }

    @Test
    void conflitDeVersion_409() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/version", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODIFICATION_CONCURRENTE"));
    }

    @Test
    void verrouNonObtenu_503AvecRetryAfter() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/verrou", ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("RESSOURCE_VERROUILLEE"));
    }

    @Test
    void defautInterne_500SansFuiteDuMessageTechnique() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/defaut", ID).header(FiltreRequestId.EN_TETE, REQUEST_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ERREUR_INTERNE"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Montant"))))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void champInvalide_400AvecLaListeDesChamps() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/formulaire", ID)
                        .header("X-Utilisateur-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"))
                .andExpect(jsonPath("$.erreurs[0].champ").value("justification"))
                .andExpect(jsonPath("$.businessCorrelationId").value(ID));
    }

    @Test
    void enTeteUtilisateurManquant_400() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/formulaire", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\": \"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @Test
    void identifiantMalForme_400SansCorrelationDansLesLogs() throws Exception {
        mockMvc.perform(post("/api/demandes/{id}/introuvable", "pas-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"))
                .andExpect(jsonPath("$.businessCorrelationId").doesNotExist());
    }

    /** Controleur minimal : une route par exception a traduire. */
    @RestController
    @RequestMapping("/api/demandes")
    static class ControleurDeTest {

        record Formulaire(@NotBlank String justification) {
        }

        @PostMapping("/{id}/introuvable")
        void introuvable(@PathVariable UUID id) {
            throw new RessourceIntrouvableException("Demande", id);
        }

        @PostMapping("/{id}/hors-sequence")
        void horsSequence(@PathVariable UUID id) {
            throw new TransitionStatutInvalideException("Le statut doit etre VALIDEE_AMG", StatutDemande.SOUMISE);
        }

        @PostMapping("/{id}/budget")
        void budget(@PathVariable UUID id) {
            throw new BudgetInsuffisantException("Budget insuffisant", new BigDecimal("536"));
        }

        @PostMapping("/{id}/version")
        void version(@PathVariable UUID id) {
            throw new OptimisticLockingFailureException("version perimee");
        }

        @PostMapping("/{id}/verrou")
        void verrou(@PathVariable UUID id) {
            throw new PessimisticLockingFailureException("verrou non obtenu");
        }

        @PostMapping("/{id}/defaut")
        void defaut(@PathVariable UUID id) {
            throw new IllegalStateException("Montant TTC non valorise sur la demande");
        }

        @PostMapping("/{id}/formulaire")
        void formulaire(@PathVariable UUID id,
                        @RequestHeader("X-Utilisateur-Id") Long utilisateurId,
                        @Valid @RequestBody Formulaire formulaire) {
        }
    }
}
