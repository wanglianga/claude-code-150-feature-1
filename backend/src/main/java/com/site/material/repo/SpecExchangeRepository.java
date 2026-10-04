package com.site.material.repo;

import com.site.material.domain.SpecExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SpecExchangeRepository extends JpaRepository<SpecExchange, Long> {

    List<SpecExchange> findAllByOrderByApplyTimeDesc();

    List<SpecExchange> findAllByOrderByConfirmTimeDesc();

    List<SpecExchange> findByCrewIdOrderByApplyTimeDesc(Long crewId);

    /** 某班组已确认的规格领错记录（用于再次申请相同材料时预警） */
    List<SpecExchange> findByCrewIdAndStatusOrderByConfirmTimeDesc(Long crewId, String status);

    long countByCrewIdAndStatusAndMaterialId(Long crewId, String status, Long materialId);

    List<SpecExchange> findByCrewIdAndStatusAndConfirmTimeBetweenOrderByConfirmTimeDesc(
            Long crewId, String status, LocalDateTime from, LocalDateTime to);

    default List<SpecExchange> findConfirmedByCrewBetween(Long crewId, LocalDateTime from, LocalDateTime to) {
        return findByCrewIdAndStatusAndConfirmTimeBetweenOrderByConfirmTimeDesc(crewId, "CONFIRMED", from, to);
    }
}
