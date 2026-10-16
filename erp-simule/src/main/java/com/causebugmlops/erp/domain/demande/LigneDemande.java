package com.causebugmlops.erp.domain.demande;

import com.causebugmlops.erp.domain.article.Article;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Ligne de detail d'une demande. {@code article} est nullable : les lignes
 * "Achat Interne" non-stockable restent en texte libre (designation/specification),
 * conformement au formulaire source qui ne requiert pas de reference article pour
 * ce flux.
 */
@Entity
@Table(name = "ligne_demande", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class LigneDemande {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ligne_demande_seq")
    @SequenceGenerator(name = "ligne_demande_seq", sequenceName = "erp.ligne_demande_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demande_achat_id", nullable = false)
    private DemandeAchatInterne demandeAchat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @Column(name = "designation_libre", length = 200)
    private String designationLibre;

    @Column(name = "specification", length = 500)
    private String specification;

    @NotNull
    @Positive
    @Column(name = "quantite", nullable = false)
    @ToString.Include
    private Integer quantite;

    @Column(name = "justification_ligne", length = 500)
    private String justificationLigne;

    @Column(name = "prix_unitaire_ht", precision = 15, scale = 2)
    private java.math.BigDecimal prixUnitaireHt;

    @jakarta.persistence.Transient
    public java.math.BigDecimal getMontantLigneHt() {
        if (prixUnitaireHt == null) {
            return null;
        }
        return prixUnitaireHt.multiply(java.math.BigDecimal.valueOf(quantite));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LigneDemande that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
