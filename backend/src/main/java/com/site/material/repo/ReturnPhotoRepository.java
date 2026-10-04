package com.site.material.repo;

import com.site.material.domain.ReturnPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReturnPhotoRepository extends JpaRepository<ReturnPhoto, Long> {

    List<ReturnPhoto> findByMaterialReturnId(Long returnId);

    List<ReturnPhoto> findByMaterialReturnIdIn(List<Long> returnIds);
}
