package com.causebugmlops.erp.verification;

import com.causebugmlops.erp.domain.budget.FamilleBudgetaire;
import com.causebugmlops.erp.domain.budget.SousFamilleBudgetaire;
import com.causebugmlops.erp.domain.common.RoleUtilisateur;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.TypeAjustement;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.common.UrgenceDemande;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.demande.LigneDemande;
import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import com.causebugmlops.erp.exception.BudgetInsuffisantException;
import com.causebugmlops.erp.repository.DemandeAchatInterneRepository;
import com.causebugmlops.erp.repository.DemandeAjustementRepository;
import com.causebugmlops.erp.repository.FamilleBudgetaireRepository;
import com.causebugmlops.erp.repository.FournisseurRepository;
import com.causebugmlops.erp.repository.PoNumeroCompteurRepository;
import com.causebugmlops.erp.repository.SousFamilleBudgetaireRepository;
import com.causebugmlops.erp.repository.UtilisateurRepository;
import com.causebugmlops.erp.service.DemandeAchatInterneService;
import com.causebugmlops.erp.service.DemandeAjustementService;
import com.causebugmlops.erp.service.PurchaseOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests de CONSTAT pour le Bloc C de l'audit (Cause.Bug.MLOps / erp-simule).
 *
 * Aucun code de production n'est modifie. Chaque test formule des hypotheses ecrites a
 * partir du code lu (fichier:ligne cites dans les libelles) et les confronte au
 * comportement reel sur une base PostgreSQL ISOLEE (creee puis supprimee par la classe).
 *
 * Lecture des resultats :
 *  - chaque ligne [CONSTAT-xx] "CONFIRME" = l'hypothese formulee est verifiee,
 *    "INFIRME" = le comportement observe est different (le test echoue et liste l'ecart) ;
 *  - T1a / T1b : constats sur le workflow d'ajustement budgetaire (hypothese : defaut present) ;
 *  - T2 : verification que le verrou pessimiste protege bien l'engagement budgetaire ;
 *  - T2c : controle du banc d'essai (sans verrou, la perte de mise a jour doit etre visible) ;
 *  - T3 : simple observation d'horodatage (jamais d'echec).
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstatsBlocCVerificationTest {

    // ------------------------------------------------------------------ banc d'essai (base isolee)

    private static final String ADMIN_URL = env("TEST_PG_ADMIN_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String PG_USER = env("TEST_PG_USER", "postgres");
    private static final String PG_PASSWORD = env("TEST_PG_PASSWORD", null);
    private static final String DB_NAME = "erp_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static boolean dbCreated = false;

    private static final BigDecimal TVA = new BigDecimal("0.2000");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String env(String name, String defaut) {
        String v = System.getenv(name);
        return (v == null || v.trim().isEmpty()) ? defaut : v;
    }

    private static synchronized void creerBaseIsolee() {
        if (dbCreated) {
            return;
        }
        if (PG_PASSWORD == null) {
            throw new IllegalStateException("Variable d'environnement TEST_PG_PASSWORD absente");
        }
        try (Connection c = DriverManager.getConnection(ADMIN_URL, PG_USER, PG_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + DB_NAME);
        } catch (SQLException e) {
            throw new IllegalStateException("Creation de la base isolee impossible: " + e.getMessage(), e);
        }
        dbCreated = true;
        System.out.println("[HARNESS] base isolee creee: " + DB_NAME + " sur " + ADMIN_URL + " (utilisateur " + PG_USER + ")");
    }

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registry) {
        creerBaseIsolee();
        final String url = ADMIN_URL.substring(0, ADMIN_URL.lastIndexOf('/') + 1) + DB_NAME;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> PG_USER);
        registry.add("spring.datasource.password", () -> PG_PASSWORD);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> PG_USER);
        registry.add("spring.flyway.password", () -> PG_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @AfterAll
    static void supprimerBaseIsolee() {
        if (!dbCreated) {
            return;
        }
        try (Connection c = DriverManager.getConnection(ADMIN_URL, PG_USER, PG_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            System.out.println("[HARNESS] base isolee supprimee: " + DB_NAME);
        } catch (SQLException e) {
            System.out.println("[HARNESS] suppression de la base isolee echouee: " + e.getMessage());
        }
    }

    // La numerotation PO s'appuie sur PoNumeroCompteurRepository (extends Repository<Object, Long>,
    // PoNumeroCompteurRepository.java:6) : remplace par un mock, aucune generation de PO n'est testee.
    @MockitoBean
    private PoNumeroCompteurRepository poNumeroCompteurRepository;

    @Autowired private DemandeAchatInterneService demandeService;
    @Autowired private DemandeAjustementService ajustementService;
    @Autowired private PurchaseOrderService purchaseOrderService;
    @Autowired private DemandeAchatInterneRepository demandeRepository;
    @Autowired private DemandeAjustementRepository ajustementRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private FournisseurRepository fournisseurRepository;
    @Autowired private FamilleBudgetaireRepository familleRepository;
    @Autowired private SousFamilleBudgetaireRepository sousFamilleRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    private static boolean enteteAffiche = false;

    @BeforeEach
    void initialiserTransactionTemplate() {
        tx = new TransactionTemplate(txManager);
        if (!enteteAffiche) {
            enteteAffiche = true;
            System.out.println("[HARNESS] PostgreSQL = " + jdbc.queryForObject("select version()", String.class));
            System.out.println("[HARNESS] Hibernate = " + org.hibernate.Version.getVersionString()
                    + " | Spring Boot = " + org.springframework.boot.SpringBootVersion.getVersion()
                    + " | JVM = " + System.getProperty("java.version") + " | fuseau JVM = " + ZoneId.systemDefault());
        }
    }

    // ------------------------------------------------------------------ outils de constat

    private static final class Constats {
        private final String tag;
        private final List<String> infirmes = new ArrayList<String>();
        private int total = 0;

        Constats(String tag) {
            this.tag = tag;
        }

        void observer(String libelle, Object valeur) {
            System.out.println("[CONSTAT-" + tag + "] observation | " + libelle + " = " + valeur);
        }

        void verifier(String libelle, boolean condition, Object attendu, Object observe) {
            total++;
            System.out.println("[CONSTAT-" + tag + "] " + (condition ? "CONFIRME" : "INFIRME ") + " | " + libelle
                    + " | attendu=" + attendu + " | observe=" + observe);
            if (!condition) {
                infirmes.add(libelle + " (attendu=" + attendu + ", observe=" + observe + ")");
            }
        }

        void conclure() {
            int ok = total - infirmes.size();
            System.out.println("[CONSTAT-" + tag + "] RESULTAT = " + ok + "/" + total + " hypotheses confirmees");
            if (!infirmes.isEmpty()) {
                fail("[" + tag + "] hypotheses infirmees: " + infirmes);
            }
        }
    }

    private static RuntimeException executer(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static String decrire(Throwable t) {
        return t == null ? "aucune exception" : t.getClass().getName() + ": " + t.getMessage();
    }

    private static boolean messageContient(Throwable t, String... morceaux) {
        if (t == null || t.getMessage() == null) {
            return false;
        }
        for (String m : morceaux) {
            if (!t.getMessage().contains(m)) {
                return false;
            }
        }
        return true;
    }

    private static boolean egal(BigDecimal valeur, String attendu) {
        return valeur != null && valeur.compareTo(new BigDecimal(attendu)) == 0;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void attendre(CountDownLatch latch, long secondes) {
        try {
            latch.await(secondes, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ fixtures (via repositories)

    private static String uniq() {
        return Long.toString(System.currentTimeMillis(), 36) + "x" + SEQ.incrementAndGet();
    }

    private Utilisateur creerUtilisateur(RoleUtilisateur role) {
        final String s = uniq();
        return tx.execute(st -> utilisateurRepository.save(Utilisateur.builder()
                .nom("Verif" + s).prenom("Test").email(role.name().toLowerCase() + "." + s + "@verif.local")
                .role(role).build()));
    }

    private Fournisseur creerFournisseur() {
        final String s = uniq();
        return tx.execute(st -> fournisseurRepository.save(Fournisseur.builder().raisonSociale("Fournisseur " + s).build()));
    }

    private Long creerSousFamille(String montantAlloue) {
        final String s = uniq();
        return tx.execute(st -> {
            FamilleBudgetaire f = familleRepository.save(FamilleBudgetaire.builder()
                    .code("F" + s).libelle("Famille " + s).montantAlloue(new BigDecimal("1000000.00")).build());
            SousFamilleBudgetaire sf = sousFamilleRepository.save(SousFamilleBudgetaire.builder()
                    .familleBudgetaire(f).code("SF" + s).libelle("Sous-famille " + s)
                    .montantAlloue(new BigDecimal(montantAlloue)).build());
            return sf.getId();
        });
    }

    /** Demande ACHAT_INTERNE au statut VALIDEE_TECHNIQUE, 1 ligne (quantite 1) au prix HT donne. */
    private UUID creerDemandeValidee(Long demandeurId, String prixUnitaireHt) {
        return tx.execute(st -> {
            DemandeAchatInterne d = DemandeAchatInterne.builder()
                    .demandeur(utilisateurRepository.getReferenceById(demandeurId))
                    .typeDemande(TypeDemande.ACHAT_INTERNE)
                    .urgence(UrgenceDemande.NORMALE)
                    .justification("Fixture de verification")
                    .statut(StatutDemande.VALIDEE_TECHNIQUE)
                    .build();
            LigneDemande l = LigneDemande.builder()
                    .demandeAchat(d).designationLibre("Article fixture").quantite(1)
                    .prixUnitaireHt(new BigDecimal(prixUnitaireHt)).build();
            d.getLignes().add(l);
            return demandeRepository.save(d).getId();
        });
    }

    // ------------------------------------------------------------------ observations (SQL brut)

    private Map<String, Object> ligneDemande(UUID id) {
        return jdbc.queryForMap("SELECT statut, montant_ht, taux_tva, montant_ttc, fournisseur_selectionne_id "
                + "FROM erp.demande_achat_interne WHERE id = ?", id);
    }

    private BigDecimal engage(Long sfId) {
        return jdbc.queryForObject("SELECT montant_engage FROM erp.sous_famille_budgetaire WHERE id = ?", BigDecimal.class, sfId);
    }

    private BigDecimal alloue(Long sfId) {
        return jdbc.queryForObject("SELECT montant_alloue FROM erp.sous_famille_budgetaire WHERE id = ?", BigDecimal.class, sfId);
    }

    private String statutAjustement(Long id) {
        return jdbc.queryForObject("SELECT statut FROM erp.demande_ajustement WHERE id = ?", String.class, id);
    }

    private String statutDemande(UUID id) {
        return jdbc.queryForObject("SELECT statut FROM erp.demande_achat_interne WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ scenario commun T1a / T1b

    private static final class Scenario {
        UUID demandeId;
        Long sfSource;
        Long sfCible;
        Long ajustementId;
        Utilisateur demandeur;
        Utilisateur acheteur;
        Utilisateur daf;
        Utilisateur amg;
        Fournisseur fournisseur;
    }

    /**
     * Etapes A, B, C communes : valorisation refusee (budget insuffisant), soumission d'un
     * ajustement SOUS_FAMILLE, decision DAF = VALIDE.
     */
    private Scenario jouerEtapesAaC(Constats c) {
        final Scenario s = new Scenario();
        s.demandeur = creerUtilisateur(RoleUtilisateur.DEMANDEUR);
        s.acheteur = creerUtilisateur(RoleUtilisateur.ACHETEUR);
        s.daf = creerUtilisateur(RoleUtilisateur.DAF);
        s.amg = creerUtilisateur(RoleUtilisateur.AMG);
        s.fournisseur = creerFournisseur();
        s.sfSource = creerSousFamille("100000.00");
        s.sfCible = creerSousFamille("500.00");
        s.demandeId = creerDemandeValidee(s.demandeur.getId(), "1000.00"); // TTC attendu = 1000 x 1.2000 = 1200

        // A : valorisation avant ajustement -> budget cible (500) < TTC (1200)
        RuntimeException a = executer(() -> demandeService.valoriser(s.demandeId, null, TVA, s.fournisseur.getId(), s.sfCible));
        c.verifier("A: valoriser (budget cible 500 < TTC 1200) leve BudgetInsuffisantException [DemandeAchatInterneService.java:159]",
                a instanceof BudgetInsuffisantException, "BudgetInsuffisantException", decrire(a));
        Map<String, Object> ra = ligneDemande(s.demandeId);
        c.verifier("A: statut de la demande inchange (rollback)", "VALIDEE_TECHNIQUE".equals(ra.get("statut")), "VALIDEE_TECHNIQUE", ra.get("statut"));
        c.verifier("A: montant_ttc reste NULL (rollback)", ra.get("montant_ttc") == null, "NULL", ra.get("montant_ttc"));
        c.verifier("A: engage de la sous-famille cible = 0", egal(engage(s.sfCible), "0"), "0", engage(s.sfCible));

        // B : soumission d'un ajustement SOUS_FAMILLE de 1000 (source -> cible)
        final Long[] ajId = new Long[1];
        RuntimeException b = executer(() -> {
            ajId[0] = ajustementService.soumettreAjustement(s.demandeId, TypeAjustement.SOUS_FAMILLE,
                    new BigDecimal("1000.00"), s.sfSource, s.sfCible, null, s.acheteur, "Fixture: ajustement budgetaire").getId();
        });
        if (b != null || ajId[0] == null) {
            fail("[" + c.tag + "] soumettreAjustement a echoue de facon inattendue: " + decrire(b));
        }
        s.ajustementId = ajId[0];
        c.verifier("B: alloue source 100000 -> 99000 (deduit des la soumission) [DemandeAjustementService.java:47]",
                egal(alloue(s.sfSource), "99000"), "99000", alloue(s.sfSource));
        c.verifier("B: alloue cible 500 -> 1500", egal(alloue(s.sfCible), "1500"), "1500", alloue(s.sfCible));
        c.verifier("B: statut demande = AJUSTEMENT_SOUS_FAMILLE_EN_COURS", "AJUSTEMENT_SOUS_FAMILLE_EN_COURS".equals(statutDemande(s.demandeId)),
                "AJUSTEMENT_SOUS_FAMILLE_EN_COURS", statutDemande(s.demandeId));
        c.verifier("B: statut ajustement = EN_ATTENTE_DAF", "EN_ATTENTE_DAF".equals(statutAjustement(s.ajustementId)),
                "EN_ATTENTE_DAF", statutAjustement(s.ajustementId));
        observerHorodatage(c, s.ajustementId);
        observerSequences(c, s.ajustementId);

        // C : decision DAF = VALIDE -> la demande retrouve son statut d'avant ajustement
        RuntimeException cc = executer(() -> ajustementService.deciderDaf(s.ajustementId, "VALIDE", s.daf, "Fixture: DAF valide"));
        c.verifier("C: deciderDaf(VALIDE) s'execute sans exception", cc == null, "aucune exception", decrire(cc));
        c.verifier("C: statut demande restaure = VALIDEE_TECHNIQUE [DemandeAjustementService.java:103]",
                "VALIDEE_TECHNIQUE".equals(statutDemande(s.demandeId)), "VALIDEE_TECHNIQUE", statutDemande(s.demandeId));
        c.verifier("C: statut ajustement = EN_ATTENTE_ACHETEUR [DemandeAjustementService.java:102]",
                "EN_ATTENTE_ACHETEUR".equals(statutAjustement(s.ajustementId)), "EN_ATTENTE_ACHETEUR", statutAjustement(s.ajustementId));
        return s;
    }

    /** T3 : observation seule (jamais d'echec) du cycle ecriture/lecture de DemandeAjustement.dateCreation. */
    private void observerHorodatage(Constats c, Long ajustementId) {
        try {
            String stocke = jdbc.queryForObject("SELECT date_creation::text FROM erp.demande_ajustement WHERE id = ?", String.class, ajustementId);
            Instant relu = ajustementRepository.findById(ajustementId).orElseThrow().getDateCreation();
            LocalDateTime stockeLdt = Timestamp.valueOf(stocke).toLocalDateTime();
            long ecartStockeVsUtcMin = Duration.between(stockeLdt, LocalDateTime.now(ZoneOffset.UTC)).toMinutes();
            long ecartReluVsNowMin = Duration.between(relu, Instant.now()).toMinutes();
            c.observer("T3 fuseau JVM", ZoneId.systemDefault());
            c.observer("T3 date_creation stockee (colonne TIMESTAMP, texte SQL)", stocke);
            c.observer("T3 ecart (stocke interprete UTC) - (maintenant UTC), en minutes", ecartStockeVsUtcMin);
            c.observer("T3 date_creation relue par l'entite (Instant)", relu);
            c.observer("T3 ecart (Instant relu) - (maintenant), en minutes", ecartReluVsNowMin);
        } catch (RuntimeException e) {
            c.observer("T3 observation impossible", decrire(e));
        }
    }

    /** Observation seule : le generateur "ajustement_seq" est declare deux fois (DemandeAjustement.java:43 et AjustementBudgetaire.java, sequences differentes). */
    private void observerSequences(Constats c, Long ajustementId) {
        try {
            Map<String, Object> s1 = jdbc.queryForMap("SELECT last_value, is_called FROM erp.demande_ajustement_seq");
            Map<String, Object> s2 = jdbc.queryForMap("SELECT last_value, is_called FROM erp.ajustement_budgetaire_seq");
            c.observer("SEQ id attribue a la DemandeAjustement creee", ajustementId);
            c.observer("SEQ erp.demande_ajustement_seq (last_value, is_called)", s1.get("last_value") + ", " + s1.get("is_called"));
            c.observer("SEQ erp.ajustement_budgetaire_seq (last_value, is_called)", s2.get("last_value") + ", " + s2.get("is_called"));
        } catch (RuntimeException e) {
            c.observer("SEQ observation impossible", decrire(e));
        }
    }

    // ------------------------------------------------------------------ T1a : defaut presume

    @Test
    @Order(1)
    void constatT1a_confirmerAcheteurContourneLaValorisationEtLeBudget() {
        final Constats c = new Constats("T1a");
        final Scenario s = jouerEtapesAaC(c);

        // D : l'acheteur confirme l'ajustement
        RuntimeException d = executer(() -> ajustementService.confirmerAcheteur(s.ajustementId, "Fixture: acheteur confirme", s.acheteur));
        c.verifier("D: confirmerAcheteur s'execute sans exception", d == null, "aucune exception", decrire(d));
        c.verifier("D: la demande passe a EN_VALORISATION SANS valorisation [DemandeAjustementService.java:134]",
                "EN_VALORISATION".equals(statutDemande(s.demandeId)), "EN_VALORISATION", statutDemande(s.demandeId));
        c.verifier("D: statut ajustement = EN_ATTENTE_AMG", "EN_ATTENTE_AMG".equals(statutAjustement(s.ajustementId)),
                "EN_ATTENTE_AMG", statutAjustement(s.ajustementId));
        Map<String, Object> rd = ligneDemande(s.demandeId);
        c.verifier("D: montant_ttc toujours NULL", rd.get("montant_ttc") == null, "NULL", rd.get("montant_ttc"));
        c.verifier("D: fournisseur_selectionne_id toujours NULL", rd.get("fournisseur_selectionne_id") == null, "NULL", rd.get("fournisseur_selectionne_id"));
        c.verifier("D: engage de la sous-famille cible toujours 0", egal(engage(s.sfCible), "0"), "0", engage(s.sfCible));

        // E : l'acheteur tente maintenant de valoriser -> bloque
        RuntimeException e = executer(() -> demandeService.valoriser(s.demandeId, null, TVA, s.fournisseur.getId(), s.sfCible));
        c.verifier("E: valoriser est BLOQUE (exige VALIDEE_TECHNIQUE) [DemandeAchatInterneService.java:120-122]",
                e instanceof IllegalStateException && messageContient(e, "VALIDEE_TECHNIQUE", "EN_VALORISATION"),
                "IllegalStateException(... VALIDEE_TECHNIQUE ... Statut actuel: EN_VALORISATION)", decrire(e));

        // F : la chaine de validation continue malgre l'absence de valorisation
        RuntimeException f1 = executer(() -> ajustementService.finaliserAmg(s.ajustementId, s.amg));
        RuntimeException f2 = executer(() -> demandeService.validerAmg(s.demandeId, true, s.amg, "Fixture: AMG valide"));
        RuntimeException f3 = executer(() -> demandeService.validerDaf(s.demandeId, true, s.daf, "Fixture: DAF valide"));
        RuntimeException f4 = executer(() -> demandeService.validerDg(s.demandeId));
        c.verifier("F: finaliserAmg accepte", f1 == null, "aucune exception", decrire(f1));
        c.verifier("F: validerAmg accepte [DemandeAchatInterneService.java:221]", f2 == null, "aucune exception", decrire(f2));
        c.verifier("F: validerDaf accepte", f3 == null, "aucune exception", decrire(f3));
        c.verifier("F: validerDg accepte", f4 == null, "aucune exception", decrire(f4));
        Map<String, Object> rf = ligneDemande(s.demandeId);
        c.verifier("F: statut final = VALIDEE_DG", "VALIDEE_DG".equals(rf.get("statut")), "VALIDEE_DG", rf.get("statut"));
        c.verifier("F: montant_ttc NULL a VALIDEE_DG", rf.get("montant_ttc") == null, "NULL", rf.get("montant_ttc"));
        c.verifier("F: fournisseur NULL a VALIDEE_DG", rf.get("fournisseur_selectionne_id") == null, "NULL", rf.get("fournisseur_selectionne_id"));
        c.verifier("F: aucun budget engage sur la sous-famille cible", egal(engage(s.sfCible), "0"), "0", engage(s.sfCible));
        c.verifier("F: statut ajustement = VALIDE", "VALIDE".equals(statutAjustement(s.ajustementId)), "VALIDE", statutAjustement(s.ajustementId));

        // G : la generation du PO est impossible (fournisseur absent)
        RuntimeException g = executer(() -> tx.execute(st -> {
            DemandeAchatInterne dem = demandeRepository.findById(s.demandeId).orElseThrow();
            return purchaseOrderService.genererDepuisDemande(dem);
        }));
        c.verifier("G: genererDepuisDemande refuse (fournisseur null) [PurchaseOrderService.java:33-35]",
                g instanceof IllegalStateException && messageContient(g, "Fournisseur doit etre selectionne"),
                "IllegalStateException(Fournisseur doit etre selectionne avant generation)", decrire(g));
        Integer nbPo = jdbc.queryForObject("SELECT count(*) FROM erp.purchase_order WHERE demande_achat_id = ?", Integer.class, s.demandeId);
        c.verifier("G: aucun purchase_order cree", nbPo != null && nbPo.intValue() == 0, "0", nbPo);

        c.conclure();
    }

    // ------------------------------------------------------------------ T1b : controle (ordre inverse)

    @Test
    @Order(2)
    void controleT1b_valoriserAvantConfirmerAcheteurFonctionne() {
        final Constats c = new Constats("T1b");
        final Scenario s = jouerEtapesAaC(c);

        // D' : l'acheteur valorise AVANT de confirmer l'ajustement
        RuntimeException v = executer(() -> demandeService.valoriser(s.demandeId, null, TVA, s.fournisseur.getId(), s.sfCible));
        c.verifier("D': valoriser reussit une fois l'ajustement valide par le DAF", v == null, "aucune exception", decrire(v));
        Map<String, Object> rv = ligneDemande(s.demandeId);
        c.verifier("D': statut = EN_VALORISATION", "EN_VALORISATION".equals(rv.get("statut")), "EN_VALORISATION", rv.get("statut"));
        c.verifier("D': montant_ttc = 1200", egal((BigDecimal) rv.get("montant_ttc"), "1200"), "1200", rv.get("montant_ttc"));
        c.verifier("D': fournisseur renseigne", rv.get("fournisseur_selectionne_id") != null, "non NULL", rv.get("fournisseur_selectionne_id"));
        c.verifier("D': engage de la sous-famille cible = 1200", egal(engage(s.sfCible), "1200"), "1200", engage(s.sfCible));

        // E' : confirmation + finalisation + chaine de validation
        RuntimeException e1 = executer(() -> ajustementService.confirmerAcheteur(s.ajustementId, "Fixture: acheteur confirme", s.acheteur));
        RuntimeException e2 = executer(() -> ajustementService.finaliserAmg(s.ajustementId, s.amg));
        RuntimeException e3 = executer(() -> demandeService.validerAmg(s.demandeId, true, s.amg, "Fixture: AMG valide"));
        RuntimeException e4 = executer(() -> demandeService.validerDaf(s.demandeId, true, s.daf, "Fixture: DAF valide"));
        RuntimeException e5 = executer(() -> demandeService.validerDg(s.demandeId));
        c.verifier("E': confirmerAcheteur accepte", e1 == null, "aucune exception", decrire(e1));
        c.verifier("E': finaliserAmg accepte", e2 == null, "aucune exception", decrire(e2));
        c.verifier("E': validerAmg accepte", e3 == null, "aucune exception", decrire(e3));
        c.verifier("E': validerDaf accepte", e4 == null, "aucune exception", decrire(e4));
        c.verifier("E': validerDg accepte", e5 == null, "aucune exception", decrire(e5));
        Map<String, Object> rf = ligneDemande(s.demandeId);
        c.verifier("E': statut final = VALIDEE_DG", "VALIDEE_DG".equals(rf.get("statut")), "VALIDEE_DG", rf.get("statut"));
        c.verifier("E': montant_ttc = 1200 a VALIDEE_DG", egal((BigDecimal) rf.get("montant_ttc"), "1200"), "1200", rf.get("montant_ttc"));
        c.verifier("E': budget engage = 1200 (une seule fois)", egal(engage(s.sfCible), "1200"), "1200", engage(s.sfCible));

        c.conclure();
    }

    // ------------------------------------------------------------------ T2 : course budgetaire (n1)

    @Test
    @Order(3)
    void constatT2_leVerrouProtegeLEngagementBudgetaireConcurrent() throws Exception {
        final Constats c = new Constats("T2");
        final Utilisateur demandeur = creerUtilisateur(RoleUtilisateur.DEMANDEUR);
        final Fournisseur fournisseur = creerFournisseur();

        // Echauffement (chargement des classes, premiere requete Hibernate) sur des donnees separees
        final Long sfEchauffement = creerSousFamille("10000.00");
        final UUID dEchauffement = creerDemandeValidee(demandeur.getId(), "500.00");
        RuntimeException w = executer(() -> demandeService.valoriser(dEchauffement, null, TVA, fournisseur.getId(), sfEchauffement));
        c.observer("echauffement (doit etre sans exception)", decrire(w));

        // Cas mesure : budget 1000, deux demandes de TTC 600 chacune sur la MEME sous-famille
        final Long sf = creerSousFamille("1000.00");
        final UUID d1 = creerDemandeValidee(demandeur.getId(), "500.00");
        final UUID d2 = creerDemandeValidee(demandeur.getId(), "500.00");
        final long maintienMs = 1500L;
        final CountDownLatch verrouPris = new CountDownLatch(1);
        final long[] dureeB = new long[1];

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A : transaction englobante qui valorise d1 puis GARDE la transaction ouverte (le verrou de
            // la sous-famille, pris dans verifierEtEngager, reste donc tenu jusqu'au commit).
            Future<RuntimeException> fa = pool.submit(new Callable<RuntimeException>() {
                @Override
                public RuntimeException call() {
                    return executer(() -> tx.execute(st -> {
                        try {
                            demandeService.valoriser(d1, null, TVA, fournisseur.getId(), sf);
                        } finally {
                            verrouPris.countDown();
                        }
                        dormir(maintienMs);
                        return null;
                    }));
                }
            });
            // Thread B : demarre des que A tient le verrou, valorise d2 (sa propre transaction)
            Future<RuntimeException> fb = pool.submit(new Callable<RuntimeException>() {
                @Override
                public RuntimeException call() throws Exception {
                    if (!verrouPris.await(30, TimeUnit.SECONDS)) {
                        return new IllegalStateException("le thread A n'a jamais pris le verrou");
                    }
                    long t0 = System.nanoTime();
                    RuntimeException e = executer(() -> demandeService.valoriser(d2, null, TVA, fournisseur.getId(), sf));
                    dureeB[0] = (System.nanoTime() - t0) / 1000000L;
                    return e;
                }
            });
            // Pendant que A garde la transaction ouverte, B doit apparaitre comme session en attente de verrou cote PostgreSQL.
            attendre(verrouPris, 30);
            dormir(700);
            List<Map<String, Object>> attentes = jdbc.queryForList(
                    "SELECT wait_event_type, wait_event, left(query, 160) AS requete FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event_type = 'Lock'");
            c.observer("sessions PostgreSQL en attente de verrou pendant que A tient la transaction", attentes);
            c.verifier("au moins une session PostgreSQL attend un verrou pendant que A tient la transaction (B est bloquee)",
                    !attentes.isEmpty(), ">= 1 session", attentes.size() + " session(s)");

            RuntimeException ea = fa.get(60, TimeUnit.SECONDS);
            RuntimeException eb = fb.get(60, TimeUnit.SECONDS);

            c.verifier("A: valoriser(d1) reussit (600 <= 1000)", ea == null, "aucune exception", decrire(ea));
            c.verifier("B: valoriser(d2) est REFUSEE apres l'attente du verrou (400 < 600) [BudgetGovernanceService.java:26-29]",
                    eb instanceof BudgetInsuffisantException, "BudgetInsuffisantException", decrire(eb));
            c.verifier("B: B a attendu la liberation du verrou de A (duree >= 1000 ms)", dureeB[0] >= 1000L, ">= 1000 ms", dureeB[0] + " ms");
        } finally {
            pool.shutdownNow();
        }

        BigDecimal engageFinal = engage(sf);
        int succes = 0;
        if ("EN_VALORISATION".equals(statutDemande(d1))) {
            succes++;
        }
        if ("EN_VALORISATION".equals(statutDemande(d2))) {
            succes++;
        }
        c.observer("nombre de valorisations reussies", succes);
        c.observer("engage final", engageFinal);
        c.verifier("engage final = 600 (une seule valorisation prise en compte)", egal(engageFinal, "600"), "600", engageFinal);
        c.verifier("invariant: engage final = 600 x nombre de valorisations reussies",
                engageFinal.compareTo(new BigDecimal("600").multiply(BigDecimal.valueOf(succes))) == 0,
                "600 x " + succes, engageFinal);
        c.verifier("invariant: engage final <= alloue", engageFinal.compareTo(alloue(sf)) <= 0, "<= " + alloue(sf), engageFinal);
        c.verifier("d1 = EN_VALORISATION", "EN_VALORISATION".equals(statutDemande(d1)), "EN_VALORISATION", statutDemande(d1));
        c.verifier("d2 reste VALIDEE_TECHNIQUE avec montant_ttc NULL",
                "VALIDEE_TECHNIQUE".equals(statutDemande(d2)) && ligneDemande(d2).get("montant_ttc") == null,
                "VALIDEE_TECHNIQUE / NULL", statutDemande(d2) + " / " + ligneDemande(d2).get("montant_ttc"));

        c.conclure();
    }

    // ------------------------------------------------------------------ T2c : controle du banc d'essai

    @Test
    @Order(4)
    void controleT2c_sansVerrouLaPerteDeMiseAJourEstDetectable() throws Exception {
        final Constats c = new Constats("T2c");
        final Long sf = creerSousFamille("1000.00");
        final CountDownLatch deuxLectures = new CountDownLatch(2);
        final BigDecimal montant = new BigDecimal("600.00");

        // Meme logique que BudgetGovernanceService.verifierEtEngager (:23-32) MAIS lecture sans verrou
        // (findById) : les deux transactions lisent engage = 0 avant qu'aucune n'ecrive.
        Callable<Boolean> tache = new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return tx.execute(st -> {
                    SousFamilleBudgetaire s = sousFamilleRepository.findById(sf).orElseThrow();
                    if (s.getMontantDisponible().compareTo(montant) < 0) {
                        return Boolean.FALSE;
                    }
                    deuxLectures.countDown();
                    attendre(deuxLectures, 10);
                    s.setMontantEngage(s.getMontantEngage().add(montant));
                    sousFamilleRepository.save(s);
                    return Boolean.TRUE;
                });
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(tache);
            Future<Boolean> f2 = pool.submit(tache);
            Boolean r1 = f1.get(60, TimeUnit.SECONDS);
            Boolean r2 = f2.get(60, TimeUnit.SECONDS);
            BigDecimal engageFinal = engage(sf);
            c.observer("resultats des deux transactions sans verrou", r1 + " / " + r2);
            c.observer("engage final", engageFinal);
            c.verifier("sans verrou, les deux engagements de 600 sont acceptes", Boolean.TRUE.equals(r1) && Boolean.TRUE.equals(r2),
                    "true / true", r1 + " / " + r2);
            c.verifier("sans verrou, l'engage final (600) viole l'invariant 600 x 2 = 1200 : perte de mise a jour visible",
                    egal(engageFinal, "600"), "600 (au lieu de 1200)", engageFinal);
        } finally {
            pool.shutdownNow();
        }
        c.conclure();
    }
}
