package com.roladio.banking.repository;

import com.roladio.banking.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}