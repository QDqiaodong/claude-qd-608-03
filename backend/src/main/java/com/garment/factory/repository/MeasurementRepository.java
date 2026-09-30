package com.garment.factory.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.Measurement;
import com.garment.factory.dto.MeasurementListItemDto;
import com.garment.factory.dto.MeasurementOrderRefDto;

import jakarta.persistence.LockModeType;

/**
 * 量体记录：列表走 JPQL 构造器表达式投影 + Slice 滚动分页。
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

    /** 下单选用历史量体时对记录加行锁：与量体编辑/删除串行，杜绝快照写一半或读到中间版本 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Measurement m WHERE m.id = :id")
    java.util.Optional<Measurement> findByIdForUpdate(@Param("id") Long id);

    /** 引用该批量体记录的订单：删除拦截要返回订单号与状态，列表提示也用同一口径 */
    @Query("SELECT new com.garment.factory.dto.MeasurementOrderRefDto("
            + "o.measurementId, o.id, o.orderNo, o.status, c.name) "
            + "FROM CustomOrder o JOIN com.garment.factory.domain.Customer c ON c.id = o.customerId "
            + "WHERE o.measurementId IN :measurementIds ORDER BY o.id ASC")
    List<MeasurementOrderRefDto> findOrderRefsByMeasurementIds(@Param("measurementIds") Collection<Long> measurementIds);

    boolean existsByRecordNo(String recordNo);

    boolean existsByRecordNoAndIdNot(String recordNo, Long id);

    long countByCustomerId(Long customerId);
}
