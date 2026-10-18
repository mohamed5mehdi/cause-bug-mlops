package com.causebugmlops.erp.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prouve les deux proprietes de la trace d'execution (ADR-010) sur un vrai
 * PostgreSQL : une operation metier ANNULEE (422, budget insuffisant) laisse
 * quand meme sa trace d'ECHEC, et chaque trace est signalee par pg_notify.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class TraceExecutionTest {

    private static final String UTILISATEUR = "X-Utilisateur-Id";
    private static final String CANAL = "trace_execution";

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
    void unEchecAnnuleLaisseSaTraceEtChaqueTraceEstNotifiee() throws Exception {
        try (Connection ecouteur = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = ecouteur.createStatement()) {
                st.execute("LISTEN " + CANAL);
            }

            long demandeur = idUtilisateur("mehdi.demandeur@demo.local");
            long managerN1 = idUtilisateur("manager.n1@demo.local");
            long fournisseur = jdbc.queryForObject(
                    "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'", Long.class);
            long infoLog = jdbc.queryForObject(
                    "SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-LOG'", Long.class);
            BigDecimal engageAvant = montantEngage("INFO-LOG");

            // Une demande de 100 000 HT (120 000 TTC) sur une enveloppe de 100 000.
            String creation = mockMvc.perform(post("/api/demandes")
                            .header(UTILISATEUR, demandeur)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"justification\": \"Licences annuelles\", "
                                    + "\"lignes\": [{\"designationLibre\": \"Suite bureautique\", \"quantite\": 1}]}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode demande = json.readTree(creation);
            String id = demande.get("id").asText();
            long ligne = demande.get("lignes").get(0).get("id").asLong();

            mockMvc.perform(post("/api/demandes/{id}/soumission", id)).andExpect(status().isOk());
            mockMvc.perform(post("/api/demandes/{id}/validation-n1", id)
                            .header(UTILISATEUR, managerN1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"valider\": true}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/demandes/{id}/validation-technique", id)).andExpect(status().isOk());
            mockMvc.perform(post("/api/demandes/{id}/valorisation", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tauxTva\": 0.20, \"fournisseurId\": " + fournisseur
                                    + ", \"sousFamilleId\": " + infoLog
                                    + ", \"prix\": [{\"ligneId\": " + ligne + ", \"prixUnitaireHt\": 100000}]}"))
                    .andExpect(status().isUnprocessableEntity());

            UUID correlation = UUID.fromString(id);

            // 1. L'operation a bien ete annulee : rien n'est engage.
            assertThat(montantEngage("INFO-LOG")).isEqualByComparingTo(engageAvant);

            // 2. ... mais sa trace d'echec existe, avec la cause et la correlation.
            List<String> chronologie = jdbc.queryForList(
                    "SELECT composant || '.' || operation || ':' || statut FROM erp.trace_execution "
                            + "WHERE business_correlation_id = ? ORDER BY debut, id", String.class, correlation);
            assertThat(chronologie).containsSubsequence(
                    "DemandeAchatInterneService.soumettre:SUCCES",
                    "DemandeAchatInterneService.validerN1:SUCCES",
                    "HistoriqueValidationService.enregistrerDecision:SUCCES",
                    "DemandeAchatInterneService.validerTechnique:SUCCES",
                    "DemandeAchatInterneService.valoriser:ECHEC",
                    "BudgetGovernanceService.verifierEtEngager:ECHEC");

            Map<String, Object> echec = jdbc.queryForMap(
                    "SELECT id, exception_type, request_id FROM erp.trace_execution "
                            + "WHERE business_correlation_id = ? AND operation = 'valoriser'", correlation);
            assertThat(echec.get("exception_type"))
                    .isEqualTo("com.causebugmlops.erp.exception.BudgetInsuffisantException");
            assertThat(echec.get("request_id")).isNotNull();

            // 3. Chaque ligne a ete notifiee sur le canal, avec son identifiant.
            List<String> notifiees = recevoir(ecouteur);
            assertThat(notifiees).contains(String.valueOf(echec.get("id")));
        }
    }

    /** Recupere les notifications deja recues (attente maximale 5 s pour la premiere). */
    private static List<String> recevoir(Connection ecouteur) throws Exception {
        PGConnection pg = ecouteur.unwrap(PGConnection.class);
        List<String> charges = new ArrayList<>();
        PGNotification[] lot = pg.getNotifications(5000);
        while (lot != null && lot.length > 0) {
            for (PGNotification n : lot) {
                if (CANAL.equals(n.getName())) {
                    charges.add(n.getParameter());
                }
            }
            lot = pg.getNotifications(500);
        }
        return charges;
    }

    private long idUtilisateur(String email) {
        return jdbc.queryForObject("SELECT id FROM erp.utilisateur WHERE email = ?", Long.class, email);
    }

    private BigDecimal montantEngage(String code) {
        return jdbc.queryForObject(
                "SELECT montant_engage FROM erp.sous_famille_budgetaire WHERE code = ?", BigDecimal.class, code);
    }
}
