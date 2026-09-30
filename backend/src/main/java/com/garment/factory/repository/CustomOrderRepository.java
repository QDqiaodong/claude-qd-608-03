package com.garment.factory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.CustomOrder;
import com.garment.factory.dto.OrderBoardCardDto;
import com.garment.factory.dto.OrderListItemDto;
import com.garment.factory.dto.OrderRefDto;

import jakarta.persistence.LockModeType;

/**
 * 定制订单：列表与看板全部走 JPQL 构造器表达式投影，状态推进走 JPQL update。
 * 量体信息一律 JOIN order_measurement_snapshot（冻结口径），不 JOIN 可编辑的 measurement。
 */
public interface CustomOrderRepository extends JpaRepository<CustomOrder, Long> {

    String SNAPSHOT_COLS = "snap.recordNo, snap.tailorName, snap.measureDate, "
            + "snap.bust, snap.waist, snap.hip, snap.shoulder, snap.sleeveLength, snap.bodyLength, "
            + "snap.pantsLength, snap.measureRemark, snap.status, snap.verifyNote, snap.source, snap.frozenAt, "
            + "snap.confirmedBy, o.measurementId";

    @Query("SELECT new com.garment.factory.dto.OrderListItemDto("
            + "o.id, o.orderNo, o.customerId, c.name, c.phone, "
            + "s.styleNo, s.name, s.category, f.fabricNo, f.name, f.color, f.colorHex, "
            + "o.measurementId, o.usageMeters, o.orderDate, o.deliveryDate, "
            + "o.fabricCost, o.craftFee, o.totalPrice, o.deposit, o.balance, o.depositPaid, o.balancePaid, "
            + "o.status, o.remark, " + SNAPSHOT_COLS + ") "
            + "FROM CustomOrder o "
            + "JOIN com.garment.factory.domain.Customer c ON c.id = o.customerId "
            + "JOIN com.garment.factory.domain.Style s ON s.id = o.styleId "
            + "JOIN com.garment.factory.domain.Fabric f ON f.id = o.fabricId "
            + "LEFT JOIN com.garment.factory.domain.OrderMeasurementSnapshot snap ON snap.orderId = o.id "
            + "WHERE (:status IS NULL OR :status = '' OR o.status = :status) "
            + "  AND (:customerId IS NULL OR o.customerId = :customerId) "
            + "  AND (:keyword IS NULL OR :keyword = '' "
            + "       OR o.orderNo LIKE CONCAT('%', :keyword, '%') "
            + "       OR c.name LIKE CONCAT('%', :keyword, '%') "
            + "       OR s.name LIKE CONCAT('%', :keyword, '%')) "
            + "ORDER BY o.deliveryDate ASC, o.id DESC")
    Slice<OrderListItemDto> sliceBy(@Param("status") String status,
                                    @Param("customerId") Long customerId,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    @Query("SELECT new com.garment.factory.dto.OrderBoardCardDto("
            + "o.id, o.orderNo, c.name, s.name, f.name, f.colorHex, o.deliveryDate, "
            + "o.totalPrice, o.usageMeters, o.status, o.balancePaid, " + SNAPSHOT_COLS + ") "
            + "FROM CustomOrder o "
            + "JOIN com.garment.factory.domain.Customer c ON c.id = o.customerId "
            + "JOIN com.garment.factory.domain.Style s ON s.id = o.styleId "
            + "JOIN com.garment.factory.domain.Fabric f ON f.id = o.fabricId "
            + "LEFT JOIN com.garment.factory.domain.OrderMeasurementSnapshot snap ON snap.orderId = o.id "
            + "ORDER BY o.deliveryDate ASC, o.id DESC")
    List<OrderBoardCardDto> findBoardCards();

    /** 引用某条量体记录的全部订单（编辑提示/删除拦截用），未交付在前便于阻断提示 */
    @Query("SELECT new com.garment.factory.dto.OrderRefDto(o.id, o.orderNo, o.status, o.status, "
            + "(CASE WHEN o.status <> 'DELIVERED' THEN TRUE ELSE FALSE END)) "
            + "FROM CustomOrder o WHERE o.measurementId = :measurementId ORDER BY o.id ASC")
    List<OrderRefDto> findRefsByMeasurementId(@Param("measurementId") Long measurementId);

    /** 一次取回多条量体记录的引用计数（量体列表批量标记，避免逐条请求）：未交付数 / 已交付数 */
    @Query("SELECT new com.garment.factory.dto.MeasurementUsageCountDto(o.measurementId, "
            + "COUNT(CASE WHEN o.status <> 'DELIVERED' THEN 1 END), "
            + "COUNT(CASE WHEN o.status = 'DELIVERED' THEN 1 END)) "
            + "FROM CustomOrder o WHERE o.measurementId IN :ids GROUP BY o.measurementId")
    List<com.garment.factory.dto.MeasurementUsageCountDto> summarizeUsageByMeasurementIds(
            @Param("ids") java.util.Collection<Long> ids);

    /** 未交付订单对量体记录的引用数：>0 时删除必须被服务端拒绝 */
    @Query("SELECT COUNT(o) FROM CustomOrder o WHERE o.measurementId = :measurementId AND o.status <> 'DELIVERED'")
    long countActiveRefsByMeasurementId(@Param("measurementId") Long measurementId);

    @Modifying
    @Query("UPDATE CustomOrder o SET o.status = :status WHERE o.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 整改相关写流程统一先锁订单行：串行化同订单的所有操作，并统一加锁顺序避免死锁 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CustomOrder o WHERE o.id = :id")
    Optional<CustomOrder> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("UPDATE CustomOrder o SET o.balancePaid = TRUE WHERE o.id = :id")
    int payBalance(@Param("id") Long id);

    long countByStyleId(Long styleId);

    long countByFabricId(Long styleId);

    long countByCustomerId(Long customerId);

    boolean existsByOrderNo(String orderNo);

    List<CustomOrder> findByDeliveryDateBeforeAndStatusNot(LocalDate date, String status);
}
