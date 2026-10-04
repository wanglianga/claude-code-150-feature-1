package com.site.material.repo;

import com.site.material.domain.MaterialReturn;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MaterialReturnRepository extends org.springframework.data.jpa.repository.JpaRepository<MaterialReturn, Long> {

    @Query("select distinct m from MaterialReturn m left join fetch m.items where m.id = :id")
    Optional<MaterialReturn> findByIdWithItems(@Param("id") Long id);

    @Query("select distinct m from MaterialReturn m left join fetch m.items order by m.applyTime desc")
    List<MaterialReturn> findAllWithItems();

    @Query("select distinct m from MaterialReturn m left join fetch m.items where m.status = :status order by m.applyTime desc")
    List<MaterialReturn> findByStatus(@Param("status") String status);

    @Query("select distinct m from MaterialReturn m left join fetch m.items where m.crewId = :crewId order by m.applyTime desc")
    List<MaterialReturn> findByCrewId(@Param("crewId") Long crewId);

    @Query("select distinct m from MaterialReturn m left join fetch m.items where m.crewId = :crewId and m.confirmTime >= :from and m.confirmTime < :to and m.status = 'CONFIRMED'")
    List<MaterialReturn> findConfirmedByCrewBetween(@Param("crewId") Long crewId,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    @Query("select distinct m from MaterialReturn m left join fetch m.items "
            + "where m.crewId = :crewId and m.reasonType = 'SPEC_WRONG' and m.status = 'CONFIRMED' "
            + "order by m.confirmTime desc")
    List<MaterialReturn> findConfirmedSpecWrongByCrew(@Param("crewId") Long crewId);
}
