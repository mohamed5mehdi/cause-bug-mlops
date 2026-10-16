package com.causebugmlops.erp.domain.fournisseur;

import com.causebugmlops.erp.domain.common.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "fournisseur", schema = "erp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Fournisseur extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "fournisseur_seq")
    @SequenceGenerator(name = "fournisseur_seq", sequenceName = "erp.fournisseur_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotBlank
    @Column(name = "raison_sociale", nullable = false, length = 200)
    @ToString.Include
    private String raisonSociale;

    @Column(name = "contact", length = 200)
    private String contact;

    @Column(name = "actif", nullable = false)
    @Builder.Default
    private boolean actif = true;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fournisseur that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
