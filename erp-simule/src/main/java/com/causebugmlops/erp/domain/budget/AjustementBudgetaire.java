package com.causebugmlops.erp.domain.budget;

import com.causebugmlops.erp.domain.common.Traceable;
import com.causebugmlops.erp.domain.common.TypeAjustement;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
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

/**
 * Sous-flux d'ajustement budgetaire (CU-02/CU-03) : {@code validePar} porte le DAF
 * si {@code typeAjustement = SOUS_FAMILLE}, le DG si {@code typeAjustement = FAMILLE}
 * - regle metier documentee dans le domaine source, non encodee ici en contrainte
 * DB (validee au niveau service applicatif, cf. Phase 3).
 */
@Entity
@Table(name = "ajustement_budgetaire", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AjustementBudgetaire extends Traceable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ajustement_seq")
    @SequenceGenerator(name = "ajustement_seq", sequenceName = "erp.ajustement_budgetaire_seq", allocationSize = 1)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demande_achat_id", nullable = false)
    private DemandeAchatInterne demandeAchat;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type_ajustement", nullable = false, length = 20)
    @ToString.Include
    private TypeAjustement typeAjustement;

    @NotNull
    @Column(name = "montant_ajustement", nullable = false, precision = 15, scale = 2)
    @ToString.Include
    private BigDecimal montantAjustement;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "valide_par_id", nullable = false)
    private Utilisateur validePar;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AjustementBudgetaire that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
