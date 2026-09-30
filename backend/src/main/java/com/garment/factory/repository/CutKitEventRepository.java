package com.garment.factory.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.garment.factory.domain.CutKitEvent;

public interface CutKitEventRepository extends JpaRepository<CutKitEvent, Long> {

    /** 时间线严格按插入顺序（自增 id）回放 */
    List<CutKitEvent> findByKitIdOrderByIdAsc(Long kitId);
}
