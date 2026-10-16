package com.causebugmlops.erp.domain.reception;

import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import com.causebugmlops.erp.domain.common.Traceable;
import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
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
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Bon de reception physique (GRN), genere par le Magasinier lors du chemin "Rupture" de CU-03. */
@Entity
@Table(name = "goods_receipt_note", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class GoodsReceiptNote extends Traceable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "grn_seq")
    @SequenceGenerator(name = "grn_seq", sequenceName = "erp.goods_receipt_note_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "magasinier_id", nullable = false)
    private Utilisateur magasinier;

    @NotNull
    @Positive
    @Column(name = "quantite_recue", nullable = false)
    private Integer quantiteRecue;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoodsReceiptNote that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
