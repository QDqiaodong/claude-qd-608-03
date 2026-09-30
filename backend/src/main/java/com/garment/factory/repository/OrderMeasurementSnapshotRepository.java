package com.garment.factory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.OrderMeasurementSnapshot;

/**
 * 订单量体快照：与订单 1:1，读取订单尺寸一律走这里。
 */
public interface OrderMeasurementSnapshotRepository extends JpaRepository<OrderMeasurementSnapshot, Long> {

    Optional<OrderMeasurementSnapshot> findByOrderId(Long orderId);

    List<OrderMeasurementSnapshot> findByOrderIdIn(List<Long> orderIds);

    /** 还没有快照的订单，按 id 升序补齐，保证多次执行的扫描顺序稳定 */
    @Query("SELECT o.id FROM CustomOrder o WHERE NOT EXISTS "
            + "(SELECT 1 FROM OrderMeasurementSnapshot s WHERE s.orderId = o.id) ORDER BY o.id ASC")
    List<Long> findOrderIdsWithoutSnapshot();
}
