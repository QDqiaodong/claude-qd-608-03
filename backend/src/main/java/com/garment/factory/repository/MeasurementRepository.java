package com.garment.factory.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.Measurement;
import com.garment.factory.dto.MeasurementListItemDto;

import jakarta.persistence.LockModeType;

/**
 * 量体记录：列表走 JPQL 构造器表达式投影 + Slice 滚动分页。
 * 下单与删除关键流程走 findByIdForUpdate 行锁，与编辑事务串行化。
 */
public interface MeasurementRepository extends JpaRepository<Measurement, Long> {

    @Query("SELECT new com.garment.factory.dto.MeasurementListItemDto("
            + "m.id, m.recordNo, m.customerId, m.customerName, m.tailorName, m.measureDate, "
            + "m.bust, m.waist, m.hip, m.shoulder, m.sleeveLength, m.bodyLength, m.pantsLength, m.remark, m.version) "
            + "FROM Measurement m "
            + "WHERE (:customerId IS NULL OR m.customerId = :customerId) "
            + "  AND (:tailorName IS NULL OR :tailorName = '' OR m.tailorName = :tailorName) "
            + "ORDER BY m.measureDate DESC, m.id DESC")
    Slice<MeasurementListItemDto> sliceBy(@Param("customerId") Long customerId,
                                          @Param("tailorName") String tailorName,
                                          Pageable pageable);

    @Query("SELECT new com.garment.factory.dto.MeasurementListItemDto("
            + "m.id, m.recordNo, m.customerId, m.customerName, m.tailorName, m.measureDate, "
            + "m.bust, m.waist, m.hip, m.shoulder, m.sleeveLength, m.bodyLength, m.pantsLength, m.remark, m.version) "
            + "FROM Measurement m WHERE m.customerId = :customerId "
            + "ORDER BY m.measureDate DESC, m.id DESC")
    Slice<MeasurementListItemDto> sliceByCustomer(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * 下单采用已有量体记录时持行锁读取：与「编辑 / 删除该记录」的事务串行，
     * 保证订单事务里冻结的永远是一个完整、确定的版本，不会与删除并发留下悬空引用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Measurement m WHERE m.id = :id")
    Optional<Measurement> findByIdForUpdate(@Param("id") Long id);

    boolean existsByRecordNo(String recordNo);

    boolean existsByRecordNoAndIdNot(String recordNo, Long id);

    long countByCustomerId(Long customerId);
}
