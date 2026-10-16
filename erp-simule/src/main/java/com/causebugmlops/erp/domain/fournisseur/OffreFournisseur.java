package com.causebugmlops.erp.domain.fournisseur;

import com.causebugmlops.erp.domain.article.Article;
import com.causebugmlops.erp.domain.common.Auditable;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bordereau des Prix Unitaires (BPU) : catalogue des prix negocies par fournisseur
 * et par article, consulte par l'Acheteur lors de la valorisation d'une demande.
 */
@Entity
@Table(name = "offre_fournisseur", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class OffreFournisseur extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "offre_fournisseur_seq")
    @SequenceGenerator(name = "offre_fournisseur_seq", sequenceName = "erp.offre_fournisseur_seq", allocationSize = 1)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fournisseur_id", nullable = false)
    private Fournisseur fournisseur;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @NotNull
    @Column(name = "prix_unitaire", nullable = false, precision = 15, scale = 2)
    @ToString.Include
    private BigDecimal prixUnitaire;

    @NotNull
    @Column(name = "date_validite", nullable = false)
    private LocalDate dateValidite;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OffreFournisseur that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
