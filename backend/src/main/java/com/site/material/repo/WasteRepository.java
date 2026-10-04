package com.site.material.repo;

import com.site.material.domain.WasteRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WasteRepository extends JpaRepository<WasteRecord, Long> {

    List<WasteRecord> findAllByOrderByCreatedTimeDesc();

    @Query("select w from WasteRecord w where w.createdTime >= :from and w.createdTime < :to order by w.createdTime desc")
    List<WasteRecord> findBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select w from WasteRecord w where w.crewId = :crewId and w.createdTime >= :from and w.createdTime < :to")
    List<WasteRecord> findByCrewBetween(@Param("crewId") Long crewId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    List<WasteRecord> findByReturnId(Long returnId);

    List<WasteRecord> findByReturnIdIn(List<Long> returnIds);
}
