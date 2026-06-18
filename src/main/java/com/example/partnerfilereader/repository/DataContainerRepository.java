package com.example.partnerfilereader.repository;

import com.example.partnerfilereader.model.DataContainer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataContainerRepository extends JpaRepository<DataContainer, String> {
    List<DataContainer> findTop100ByOrderByCreatedDateDesc();

    List<DataContainer> findByIdentifyAndReconciliationDateOrderByCreatedDateDesc(
            String identify,
            String reconciliationDate
    );

    List<DataContainer> findByIdentifyOrderByCreatedDateDesc(String identify);

    Page<DataContainer> findByIdentifyAndReconciliationDateOrderByCreatedDateDesc(
            String identify,
            String reconciliationDate,
            Pageable pageable
    );

    Page<DataContainer> findByIdentifyOrderByCreatedDateDesc(String identify, Pageable pageable);
}
