package com.causebugmlops.erp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours HTTP complet d'une demande d'Achat Interne, de BROUILLON a PO_GENERE, sur
 * la pile reelle (filtre, controleur, services, PostgreSQL 16 ephemere avec les
 * donnees de demonstration), puis verification du resultat EN BASE.
 * <p>
 * Remplace la verification manuelle de J2b par un test rejouable (ADR-009).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ParcoursDemandeAchatHttpTest {

    private static final String UTILISATEUR = "X-Utilisateur-Id";
    private static final String DECISION_OK = "{\"valider\": true, \"commentaire\": \"OK\"}";
    private static final BigDecimal PRIX_UNITAIRE_HT = new BigDecimal("800");
    /** 3 unites x 800 HT = 2 400 HT ; x 1,20 de TVA = 2 880 TTC. */
    private static final BigDecimal TTC_ATTENDU = new BigDecimal("2880.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("causebugmlops");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Test
    void parcoursCompletJusquAuPurchaseOrder_etTraceEnBase() throws Exception {
        long demandeur = idUtilisateur("mehdi.demandeur@demo.local");
        long managerN1 = idUtilisateur("manager.n1@demo.local");
        long amg = idUtilisateur("amg@demo.local");
        long daf = idUtilisateur("daf@demo.local");
        long fournisseur = jdbc.queryForObject(
                "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'", Long.class);
        long infoMat = jdbc.queryForObject(
                "SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-MAT'", Long.class);
        BigDecimal engageAvant = montantEngage("INFO-MAT");

        // 1. Creation : 201 + X-Request-Id renvoye
        String creation = mockMvc.perform(post("/api/demandes")
                        .header(UTILISATEUR, demandeur)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"justification": "Postes de travail equipe projet",
                                 "lignes": [
                                   {"designationLibre": "Ordinateur portable", "quantite": 2},
                                   {"designationLibre": "Ecran 27 pouces", "quantite": 1}
                                 ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andReturn().getResponse().getContentAsString();

        JsonNode demande = json.readTree(creation);
        String id = demande.get("id").asText();
        List<Long> lignes = new ArrayList<>();
        demande.get("lignes").forEach(l -> lignes.add(l.get("id").asLong()));
        assertThat(lignes).hasSize(2);

        // 2. Workflow de validation hierarchique
        etape(post("/api/demandes/{id}/soumission", id), "SOUMISE");
        etape(decision("/api/demandes/{id}/validation-n1", id, managerN1), "VALIDEE_N1");
        etape(post("/api/demandes/{id}/validation-technique", id), "VALIDEE_TECHNIQUE");
        etape(post("/api/demandes/{id}/valorisation", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(valorisation(lignes, fournisseur, infoMat)), "EN_VALORISATION");
        etape(decision("/api/demandes/{id}/validation-amg", id, amg), "VALIDEE_AMG");
        etape(decision("/api/demandes/{id}/validation-daf", id, daf), "VALIDEE_DAF");
        etape(post("/api/demandes/{id}/validation-dg", id), "VALIDEE_DG");

        // 3. Generation du Purchase Order
        mockMvc.perform(post("/api/demandes/{id}/purchase-order", id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroPo").exists());

        // 4. Action hors sequence apres la fin du workflow : 409 lisible
        mockMvc.perform(post("/api/demandes/{id}/validation-dg", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_STATUT_INVALIDE"))
                .andExpect(jsonPath("$.statutActuel").value("PO_GENERE"))
                .andExpect(jsonPath("$.businessCorrelationId").value(id));

        // 5. Verifications en base
        UUID uuid = UUID.fromString(id);
        assertThat(jdbc.queryForObject(
                "SELECT statut FROM erp.demande_achat_interne WHERE id = ?", String.class, uuid))
                .isEqualTo("PO_GENERE");

        // Piste d'audit : N+1, AMG, DAF. Les etapes technique et DG n'ecrivent rien (defaut E7, connu).
        assertThat(jdbc.queryForList(
                "SELECT etape || ':' || decision FROM erp.historique_validation "
                        + "WHERE business_correlation_id = ? ORDER BY id", String.class, uuid))
                .containsExactly("N1:VALIDE", "AMG:VALIDE", "DAF:VALIDE");

        assertThat(montantEngage("INFO-MAT").subtract(engageAvant)).isEqualByComparingTo(TTC_ATTENDU);

        assertThat(jdbc.queryForObject(
                "SELECT montant_total FROM erp.purchase_order WHERE demande_achat_id = ?", BigDecimal.class, uuid))
                .isEqualByComparingTo(TTC_ATTENDU);
    }

    private void etape(MockHttpServletRequestBuilder requete, String statutAttendu) throws Exception {
        mockMvc.perform(requete)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value(statutAttendu));
    }

    private static MockHttpServletRequestBuilder decision(String route, String id, long valideur) {
        return post(route, id)
                .header(UTILISATEUR, valideur)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DECISION_OK);
    }

    private static String valorisation(List<Long> lignes, long fournisseur, long sousFamille) {
        StringBuilder prix = new StringBuilder();
        for (Long ligne : lignes) {
            if (prix.length() > 0) {
                prix.append(',');
            }
            prix.append("{\"ligneId\": ").append(ligne)
                    .append(", \"prixUnitaireHt\": ").append(PRIX_UNITAIRE_HT).append('}');
        }
        return "{\"tauxTva\": 0.20, \"fournisseurId\": " + fournisseur
                + ", \"sousFamilleId\": " + sousFamille + ", \"prix\": [" + prix + "]}";
    }

    private long idUtilisateur(String email) {
        return jdbc.queryForObject("SELECT id FROM erp.utilisateur WHERE email = ?", Long.class, email);
    }

    private BigDecimal montantEngage(String code) {
        return jdbc.queryForObject(
                "SELECT montant_engage FROM erp.sous_famille_budgetaire WHERE code = ?", BigDecimal.class, code);
    }
}
