package com.example.partnerfilereader.repository;

import com.example.partnerfilereader.model.InternalTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InternalTransactionRepository extends JpaRepository<InternalTransaction, String> {
    List<InternalTransaction> findTop100ByOrderByCreatedDateDesc();

    List<InternalTransaction> findByProvider(String provider);
}
