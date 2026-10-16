package com.causebugmlops.erp.domain.budget;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Enveloppe budgetaire de premier niveau. Le montant utilise le type {@link BigDecimal}
 * (jamais double/float) : un arrondi flottant sur un montant engage serait exactement
 * le type de "calcul faux" que Cause.Bug.MLOps doit pouvoir diagnostiquer - la premiere
 * regle est donc de ne pas l'introduire par negligence dans le modele lui-meme.
 * <p>
 * La coherence transactionnelle de {@code montantEngage} lors d'un engagement
 * concurrent est garantie par un verrou pessimiste au niveau repository
 * ({@link com.causebugmlops.erp.repository.FamilleBudgetaireRepository#findByIdForUpdate}),
 * pas par un champ {@code @Version} : voir la note d'architecture Phase 2 sur la
 * strategie de verrouillage.
 */
@Entity
@Table(name = "famille_budgetaire", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_famille_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class FamilleBudgetaire extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "famille_seq")
    @SequenceGenerator(name = "famille_seq", sequenceName = "erp.famille_budgetaire_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

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
        if (!(o instanceof FamilleBudgetaire that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
