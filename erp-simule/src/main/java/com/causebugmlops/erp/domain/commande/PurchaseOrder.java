package com.causebugmlops.erp.domain.commande;

import com.causebugmlops.erp.domain.common.Auditable;
import com.causebugmlops.erp.domain.common.StatutPurchaseOrder;
import com.causebugmlops.erp.domain.demande.DemandeAchatInterne;
import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
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
import jakarta.persistence.OneToOne;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Engagement juridique avec un fournisseur externe. Contrairement a
 * {@code DemandeAchatInterne}, le cycle de vie du PO est une machine a etats
 * stricte et lineaire (DRAFT -&gt; APPROVED -&gt; POSTED), cf. le domaine source.
 * Reference unidirectionnelle vers {@code DemandeAchatInterne} (pas de champ
 * retour) : un PO ne peut exister sans demande, l'inverse n'est pas vrai avant
 * generation.
 */
@Entity
@Table(name = "purchase_order", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_po_numero", columnNames = "numero_po"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class PurchaseOrder extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "purchase_order_seq")
    @SequenceGenerator(name = "purchase_order_seq", sequenceName = "erp.purchase_order_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demande_achat_id", nullable = false, unique = true)
    private DemandeAchatInterne demandeAchat;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fournisseur_id", nullable = false)
    private Fournisseur fournisseur;

    @NotBlank
    @Column(name = "numero_po", nullable = false, length = 40)
    @ToString.Include
    private String numeroPo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    @ToString.Include
    private StatutPurchaseOrder statut;

    @NotNull
    @Column(name = "montant_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantTotal;

    @Column(name = "date_generation", nullable = false)
    private Instant dateGeneration;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<LignePurchaseOrder> lignes = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseOrder that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
