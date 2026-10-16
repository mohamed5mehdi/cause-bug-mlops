package com.causebugmlops.erp.domain.reception;

import com.causebugmlops.erp.domain.common.StatutGrc;
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
import jakarta.persistence.OneToOne;
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
 * Rapprochement comptable (GRC). Reference explicitement le {@link GoodsReceiptNote},
 * jamais le {@code PurchaseOrder} directement : la sequence documentaire GRN -&gt; GRC
 * est imposee au niveau du modele (FK NOT NULL sur goods_receipt_note_id), un GRC
 * sans GRN correspondant est donc structurellement impossible par le schema seul.
 * C'est un choix deliberement discriminant : le contourner necessiterait un bug
 * applicatif explicite (ex. insertion directe hors couche service), ce qui en fait
 * un excellent scenario de fault injection "donnee manquante" pour Cause.Bug.MLOps.
 */
@Entity
@Table(name = "goods_receipt_clearing", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class GoodsReceiptClearing extends Traceable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "grc_seq")
    @SequenceGenerator(name = "grc_seq", sequenceName = "erp.goods_receipt_clearing_seq", allocationSize = 1)
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_note_id", nullable = false, unique = true)
    private GoodsReceiptNote goodsReceiptNote;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comptable_id", nullable = false)
    private Utilisateur comptable;

    @NotNull
    @Column(name = "montant_facture", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantFacture;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    @ToString.Include
    private StatutGrc statut;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoodsReceiptClearing that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
