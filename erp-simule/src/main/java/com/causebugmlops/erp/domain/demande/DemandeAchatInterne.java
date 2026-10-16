package com.causebugmlops.erp.domain.demande;

import com.causebugmlops.erp.domain.article.Article;
import com.causebugmlops.erp.domain.budget.SousFamilleBudgetaire;
import com.causebugmlops.erp.domain.common.Auditable;
import com.causebugmlops.erp.domain.common.EtapeValidation;
import com.causebugmlops.erp.domain.common.StatutDemande;
import com.causebugmlops.erp.domain.common.TypeAjustement;
import com.causebugmlops.erp.domain.common.TypeDemande;
import com.causebugmlops.erp.domain.common.UrgenceDemande;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entite pivot du domaine : centralise les deux flux Achat Interne et Achat de
 * Pieces (discrimines par {@link TypeDemande}), conformement au constat du rapport
 * source ("DemandeAchatInterne... centralise les flux d'achats normaux et de
 * pieces de rechange").
 * <p>
 * L'identifiant est un UUID genere serveur : il sert directement de
 * Business-Correlation-ID pour la strategie de tracabilite distribuee (Phase 1),
 * evitant une table de mapping dediee entre l'entite metier et son identifiant de
 * correlation.
 * <p>
 * Verrou optimiste ({@code @Version}) : un seul acteur traite une demande a la
 * fois dans le workflow sequentiel, le risque de conflit concurrent est faible et
 * la detection via {@code OptimisticLockException} est preferable a un verrou
 * bloquant pour une entite consultee frequemment par un dashboard.
 */
@Entity
@Table(name = "demande_achat_interne", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class DemandeAchatInterne extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @ToString.Include
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type_demande", nullable = false, updatable = false, length = 20)
    @ToString.Include
    private TypeDemande typeDemande;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demandeur_id", nullable = false)
    private Utilisateur demandeur;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "urgence", nullable = false, length = 20)
    private UrgenceDemande urgence;

    @Column(name = "justification", length = 2000)
    private String justification;

    /** Renseignee des lors que la demande engage un budget (hors chemin "En Stock" pur). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sous_famille_budgetaire_id")
    private SousFamilleBudgetaire sousFamilleBudgetaire;

    /** Renseignee pour le flux ACHAT_PIECE lors de la verification automatique de stock. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_reference_id")
    private Article articleReference;

    @Column(name = "montant_ht", precision = 15, scale = 2)
    private BigDecimal montantHt;

    @Column(name = "taux_tva", precision = 5, scale = 4)
    private BigDecimal tauxTva;

    @Column(name = "montant_ttc", precision = 15, scale = 2)
    private BigDecimal montantTtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_selectionne_id")
    private com.causebugmlops.erp.domain.fournisseur.Fournisseur fournisseurSelectionne;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 40)
    @ToString.Include
    private StatutDemande statut;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_ajustement", length = 50)
    private TypeAjustement typeAjustement;

    /**
     * Regle metier conditionnelle (bypass N+1 pour rupture SAV urgente, cf. flux
     * documente) : deliberement modelisee comme un attribut plutot qu'une branche
     * de la machine a etats, pour eviter une explosion combinatoire des statuts.
     */
    @Column(name = "bypass_validation_hierarchique", nullable = false)
    @Builder.Default
    private boolean bypassValidationHierarchique = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "etape_rejet", length = 30)
    private EtapeValidation etapeRejet;

    @Column(name = "motif_rejet", length = 1000)
    private String motifRejet;

    @Column(name = "date_soumission")
    private Instant dateSoumission;

    @OneToMany(mappedBy = "demandeAchat", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<LigneDemande> lignes = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DemandeAchatInterne that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
