package com.causebugmlops.erp.domain.budget;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Enveloppe budgetaire de second niveau (rattachee a une {@link FamilleBudgetaire}).
 * C'est ELLE, et non la famille, qui est verifiee/debitee dans le cas nominal
 * (CU-01 etape 5) : c'est donc le point precis a neutraliser pour le scenario de
 * fault injection "race condition budgetaire" (bug candidat n1).
 */
@Entity
@Table(name = "sous_famille_budgetaire", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_sous_famille_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class SousFamilleBudgetaire extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sous_famille_seq")
    @SequenceGenerator(name = "sous_famille_seq", sequenceName = "erp.sous_famille_budgetaire_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "famille_budgetaire_id", nullable = false)
    private FamilleBudgetaire familleBudgetaire;

    @NotBlank
    @Column(name = "code", nullable = false, length = 30)
    @ToString.Include
    private String code;

    @NotBlank
    @Column(name = "libelle", nullable = false, length = 150)
    private String libelle;

    @NotNull
    @Column(name = "montant_alloue", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantAlloue;

    @NotNull
    @Column(name = "montant_engage", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantEngage = BigDecimal.ZERO;

    public BigDecimal getMontantDisponible() {
        return montantAlloue.subtract(montantEngage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SousFamilleBudgetaire that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
