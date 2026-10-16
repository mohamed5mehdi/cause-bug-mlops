package com.causebugmlops.erp;

import com.causebugmlops.erp.domain.article.Article;
import com.causebugmlops.erp.repository.ArticleRepository;
import com.causebugmlops.erp.repository.PoNumeroCompteurRepository;
import com.causebugmlops.erp.service.StockGovernanceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "spring.jpa.properties.hibernate.format_sql=true",
        "logging.level.org.hibernate.SQL=DEBUG",
        "spring.jpa.hibernate.ddl-auto=none"
})
public class StockGovernanceConcurrencyIT {

    // Exclusion ciblée : PoNumeroCompteurRepository étend Repository<Object,Long>
    // (Object n'est pas un @Entity). @MockBean empêche Spring Data JPA de tenter
    // le bootstrap de ce repository cassé dans ce contexte de test uniquement.
    @MockBean
    private PoNumeroCompteurRepository poNumeroCompteurRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private StockGovernanceService stockGovernanceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Test
    public void testPessimisticLockWithoutRefresh() throws InterruptedException {
        // Setup
        Article article = Article.builder()
                .reference("REF-" + System.currentTimeMillis())
                .designation("Article Test Concurrency")
                .quantiteEnStock(10)
                .build();
        article = articleRepository.save(article);
        final Long id = article.getId();

        CountDownLatch latchA1Done = new CountDownLatch(1);
        CountDownLatch latchBCommitted = new CountDownLatch(1);

        AtomicReference<Integer> stockA1 = new AtomicReference<>();
        AtomicReference<Integer> stockA2 = new AtomicReference<>();
        AtomicReference<Integer> stockA2Native = new AtomicReference<>();
        AtomicReference<Boolean> identiteObjet = new AtomicReference<>();
        AtomicReference<Exception> exceptionA5 = new AtomicReference<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Thread threadA = new Thread(() -> {
            txTemplate.execute(status -> {
                // A1. Lire l'Article via articleRepository.findById(id) (SANS verrou).
                Article a1 = articleRepository.findById(id).orElseThrow();
                stockA1.set(a1.getQuantiteEnStock());

                // Signal A1 done
                latchA1Done.countDown();

                // A2. Attendre que la transaction B a committé.
                try {
                    latchBCommitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // A3. Appeler articleRepository.findByIdForUpdate(id) SUR LA MÊME instance de transaction/EntityManager que A1.
                Article a2 = articleRepository.findByIdForUpdate(id).orElseThrow();
                stockA2.set(a2.getQuantiteEnStock());
                identiteObjet.set(a1 == a2);

                // A4. Lire la valeur réelle en base à cet instant, DANS LA MÊME transaction, via une requête native
                Number nativeVal = (Number) entityManager.createNativeQuery("SELECT quantite_en_stock FROM erp.article WHERE id = ?1")
                        .setParameter(1, id)
                        .getSingleResult();
                stockA2Native.set(nativeVal != null ? nativeVal.intValue() : null);

                // A5. Appeler directement stockGovernanceService.verifierStock(id, 8)
                try {
                    stockGovernanceService.verifierStock(id, 8);
                } catch (Exception e) {
                    exceptionA5.set(e);
                }

                // A6. Rollback la transaction A
                status.setRollbackOnly();
                return null;
            });
        });

        Thread threadB = new Thread(() -> {
            try {
                latchA1Done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // B1. Démarrer une transaction indépendante
            txTemplate.execute(status -> {
                // B2. Lire le même Article SANS verrou, décrémenter quantiteEnStock de 8
                Article bArt = articleRepository.findById(id).orElseThrow();
                bArt.setQuantiteEnStock(bArt.getQuantiteEnStock() - 8);
                articleRepository.save(bArt);
                // B3. Commit
                return null; 
            });

            // B4. Libérer le signal pour A
            latchBCommitted.countDown();
        });

        threadA.start();
        threadB.start();

        threadA.join();
        threadB.join();

        System.out.println("\n\n==========================================================");
        System.out.println("RÉSULTATS DE L'EXÉCUTION :");
        System.out.println("stockA1 | stockA2 | stockA2_native | identiteObjet | exception levée en A5");
        System.out.printf("%d | %d | %d | %b | %s%n",
                stockA1.get(),
                stockA2.get(),
                stockA2Native.get(),
                identiteObjet.get(),
                exceptionA5.get() != null ? ("Oui, " + exceptionA5.get().getClass().getSimpleName()) : "Non");
        System.out.println("==========================================================\n\n");
    }
}
