package com.causebugmlops.erp.domain.ajustement;

import com.causebugmlops.erp.domain.common.StatutAjustement;
import com.causebugmlops.erp.domain.common.StatutDemande;
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
import java.time.Instant;

@Entity
@Table(name = "demande_ajustement", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class DemandeAjustement {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ajustement_seq")
    @SequenceGenerator(name = "ajustement_seq", sequenceName = "erp.demande_ajustement_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demande_interne_id")
    private DemandeAchatInterne demandeInterne;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    @ToString.Include
    private TypeAjustement type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 50)
    @ToString.Include
    private StatutAjustement statut;

    @Column(name = "source_sous_famille_id")
    private Long sourceSousFamilleId;

    @Column(name = "cible_sous_famille_id")
    private Long cibleSousFamilleId;

    @Column(name = "famille_cible_id")
    private Long familleCibleId;

    @NotNull
    @Column(name = "montant_demande", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantDemande;

    @Column(name = "montant_final", precision = 15, scale = 2)
    private BigDecimal montantFinal;

    @NotNull
    @Column(name = "justification_acheteur", nullable = false, length = 1000)
    private String justificationAcheteur;

    @Column(name = "justification_valideur", length = 1000)
    private String justificationValideur;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statut_demande_interne_avant_ajustement", nullable = false, length = 50)
    private StatutDemande statutDemandeInterneAvantAjustement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acheteur_id")
    private Utilisateur acheteur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "valideur_id")
    private Utilisateur valideur;

    @NotNull
    @Column(name = "date_creation", nullable = false)
    private Instant dateCreation;

    @Column(name = "date_decision")
    private Instant dateDecision;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DemandeAjustement that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
