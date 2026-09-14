package com.roladio.banking.repository;

import com.roladio.banking.model.Client;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Client c where c.id = :id and c.closed = false")
    Optional<Client> findByIdForUpdate(@Param("id") Long id);

    List<Client> findAllByClosedFalse();

    Optional<Client> findByIdAndClosedFalse(Long id);
}
