package com.causebugmlops.erp.domain.budget;

import com.causebugmlops.erp.domain.common.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
 * Pool budgetaire dedie aux pieces detachees (ACHAT_PIECE), etanche des
 * sous-familles budgetaires generales.
 * <p>
 * Miroir fidele du modele du depot reel adapte aux conventions erp-simule
 * (identifiant {@code Long} genere par sequence, verrou optimiste {@code @Version}
 * en defense en profondeur couple au verrou pessimiste repository).
 */
@Entity
@Table(name = "budget_pieces", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_budget_pieces_exercice", columnNames = "exercice"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class BudgetPieces extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "budget_pieces_seq")
    @SequenceGenerator(name = "budget_pieces_seq", sequenceName = "erp.budget_pieces_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotBlank
    @Column(name = "exercice", nullable = false, unique = true, length = 4)
    @ToString.Include
    private String exercice;

    @NotNull
    @Column(name = "budget_initial", nullable = false, precision = 15, scale = 2)
    @ToString.Include
    private BigDecimal budgetInitial;

    @NotNull
    @Column(name = "budget_engage", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    @ToString.Include
    private BigDecimal budgetEngage = BigDecimal.ZERO;

    @NotNull
    @Column(name = "budget_restant", nullable = false, precision = 15, scale = 2)
    @ToString.Include
    private BigDecimal budgetRestant;

    @Version
    @Column(name = "version")
    private Long version;

    public boolean hasEnoughBudget(BigDecimal montant) {
        return montant != null && this.budgetRestant != null && this.budgetRestant.compareTo(montant) >= 0;
    }

    public void deductBudget(BigDecimal montant) {
        if (montant == null || montant.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Le montant doit etre strictement positif: " + montant);
        }
        if (!hasEnoughBudget(montant)) {
            throw new IllegalStateException(String.format(
                    "Budget insuffisant pour l'exercice %s: demande=%s, budget_restant=%s",
                    exercice, montant, budgetRestant));
        }
        this.budgetRestant = this.budgetRestant.subtract(montant);
        this.budgetEngage = this.budgetEngage.add(montant);
    }

    public void addBudget(BigDecimal montant) {
        if (montant == null || montant.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Le montant doit etre strictement positif: " + montant);
        }
        BigDecimal restantApres = this.budgetRestant.add(montant);
        if (restantApres.compareTo(this.budgetInitial) > 0) {
            throw new IllegalStateException(String.format(
                    "Garde anti sur-restitution violee pour l'exercice %s: restantApres (%s) > budgetInitial (%s)",
                    exercice, restantApres, this.budgetInitial));
        }
        this.budgetRestant = restantApres;
        this.budgetEngage = this.budgetEngage.subtract(montant);
    }

    @PrePersist
    @PreUpdate
    public void checkIntegrite() {
        if (budgetRestant == null || budgetRestant.compareTo(BigDecimal.ZERO) < 0
                || (budgetInitial != null && budgetRestant.compareTo(budgetInitial) > 0)) {
            throw new IllegalStateException(String.format(
                    "Integrite budget pieces violee pour l'exercice %s: budgetRestant=%s, budgetInitial=%s",
                    exercice, budgetRestant, budgetInitial));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BudgetPieces that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
