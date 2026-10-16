package com.causebugmlops.erp.domain.utilisateur;

import com.causebugmlops.erp.domain.common.Auditable;
import com.causebugmlops.erp.domain.common.RoleUtilisateur;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "utilisateur", schema = "erp",
        uniqueConstraints = @UniqueConstraint(name = "uk_utilisateur_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Utilisateur extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "utilisateur_seq")
    @SequenceGenerator(name = "utilisateur_seq", sequenceName = "erp.utilisateur_seq", allocationSize = 1)
    @ToString.Include
    private Long id;

    @NotBlank
    @Column(name = "nom", nullable = false, length = 100)
    @ToString.Include
    private String nom;

    @NotBlank
    @Column(name = "prenom", nullable = false, length = 100)
    private String prenom;

    @Email
    @NotBlank
    @Column(name = "email", nullable = false, length = 180)
    @ToString.Include
    private String email;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    @ToString.Include
    private RoleUtilisateur role;

    @Column(name = "actif", nullable = false)
    @Builder.Default
    private boolean actif = true;

    /**
     * equals/hashCode bases exclusivement sur l'identifiant (pattern recommande pour
     * les entites JPA - cf. Vlad Mihalcea) : evite les incoherences liees aux proxies
     * Hibernate et aux entites transientes placees dans des collections avant
     * persistance. hashCode constant par classe (jamais base sur des champs mutables).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Utilisateur that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
