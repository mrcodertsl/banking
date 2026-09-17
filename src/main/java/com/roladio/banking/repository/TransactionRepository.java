package com.roladio.banking.repository;

import com.roladio.banking.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("""
            select t from Transaction t
            join fetch t.from
            join fetch t.to
            where t.from.id = :clientId or t.to.id = :clientId
            order by t.createdAt desc
            """)
    List<Transaction> findHistoryForClient(@Param("clientId") Long clientId);

    @Query("""
        select t from Transaction t
        join fetch t.from
        join fetch t.to
        where (t.from.id = :clientId or t.to.id = :clientId)
          and (:minAmount is null or t.amount >= :minAmount)
          and (:maxAmount is null or t.amount <= :maxAmount)
          and (:fromInstant is null or t.createdAt >= :fromInstant)
          and (:toInstant is null or t.createdAt < :toInstant)
          and (:counterpartyId is null or t.from.id = :counterpartyId or t.to.id = :counterpartyId)
        order by t.createdAt desc
        """)
    List<Transaction> search(@Param("clientId") Long clientId,
                             @Param("minAmount") BigDecimal minAmount,
                             @Param("maxAmount") BigDecimal maxAmount,
                             @Param("fromInstant") Instant fromInstant,
                             @Param("toInstant") Instant toInstant,
                             @Param("counterpartyId") Long counterpartyId);
}