package com.garment.factory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.OrderMeasurementSnapshot;

import jakarta.persistence.LockModeType;

/**
 * 订单量体快照：一单一份（uk_order_snapshot）。补齐/人工确认先锁快照行，保证并发只形成一份。
 */
public interface OrderMeasurementSnapshotRepository extends JpaRepository<OrderMeasurementSnapshot, Long> {

    Optional<OrderMeasurementSnapshot> findByOrderId(Long orderId);

    List<OrderMeasurementSnapshot> findByOrderIdIn(Collection<Long> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM OrderMeasurementSnapshot s WHERE s.orderId = :orderId")
    Optional<OrderMeasurementSnapshot> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    /** 待自动补齐的订单：还没有任何快照（含已人工挂待核对/已确认之外的全部漏网订单） */
    @Query("SELECT o.id FROM CustomOrder o WHERE NOT EXISTS "
            + "(SELECT 1 FROM OrderMeasurementSnapshot s WHERE s.orderId = o.id) ORDER BY o.id ASC")
    List<Long> findOrderIdsWithoutSnapshot();
}
