package com.causebugmlops.erp.domain.demande;

import com.causebugmlops.erp.domain.common.DecisionValidation;
import com.causebugmlops.erp.domain.common.EtapeValidation;
import com.causebugmlops.erp.domain.common.Traceable;
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

import java.util.UUID;

/**
 * Piste d'audit FONCTIONNELLE (qui a valide/rejete quoi, quand) - a ne pas confondre
 * avec la table technique {@code transaction_trace_event} (Phase 1), alimentee
 * independamment par l'aspect AOP uniquement lors d'exceptions. Table append-only
 * par construction ({@code extends Traceable}) : aucun champ n'est jamais mis a
 * jour apres creation.
 * <p>
 * {@code businessCorrelationId} est denormalise (duplique depuis
 * {@code demandeAchat.id}) pour permettre a aiops-engine de requeter directement
 * cette table sans jointure lors de la reconstitution du cycle de vie complet
 * d'une demande.
 */
@Entity
@Table(name = "historique_validation", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class HistoriqueValidation extends Traceable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "historique_validation_seq")
    @SequenceGenerator(name = "historique_validation_seq", sequenceName = "erp.historique_validation_seq", allocationSize = 1)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demande_achat_id", nullable = false)
    private DemandeAchatInterne demandeAchat;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "etape", nullable = false, length = 30)
    @ToString.Include
    private EtapeValidation etape;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acteur_id", nullable = false)
    private Utilisateur acteur;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    @ToString.Include
    private DecisionValidation decision;

    @Column(name = "commentaire", length = 1000)
    private String commentaire;

    @NotNull
    @Column(name = "business_correlation_id", nullable = false)
    @ToString.Include
    private UUID businessCorrelationId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistoriqueValidation that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
