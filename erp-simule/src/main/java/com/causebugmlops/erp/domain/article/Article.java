package com.causebugmlops.erp.domain.article;

import com.causebugmlops.erp.domain.common.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Referentiel articles + niveau de stock. La verification de disponibilite et la
 * decrementation lors du chemin "En Stock" de l'Achat de Pieces (CU-03) sont
 * protegees par un verrou pessimiste au niveau repository
 * ({@link com.causebugmlops.erp.repository.ArticleRepository#findByIdForUpdate}) -
 * bug candidat n2 pour le module de fault injection (survente concurrente).
 */
@Entity
@Table(name = "article", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_article_reference", columnNames = "reference"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Article extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "article_seq")
    @SequenceGenerator(name = "article_seq", sequenceName = "erp.article_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotBlank
    @Column(name = "reference", nullable = false, length = 50)
    @ToString.Include
    private String reference;

    @NotBlank
    @Column(name = "designation", nullable = false, length = 200)
    private String designation;

    @Column(name = "categorie", length = 100)
    private String categorie;

    @NotNull
    @PositiveOrZero
    @Column(name = "quantite_en_stock", nullable = false)
    @Builder.Default
    private Integer quantiteEnStock = 0;

    @Column(name = "seuil_alerte")
    private Integer seuilAlerte;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Article that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
