package com.garment.factory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.CutKit;

import jakarta.persistence.LockModeType;

/**
 * 裁片套件：所有写流程统一走 findByIdForUpdate 悲观行锁串行化，
 * 保证并发交接/接收/异常登记在同一套件上只有一次成功，且不留下半条记录。
 */
public interface CutKitRepository extends JpaRepository<CutKit, Long> {

    /** 悲观行锁：SELECT ... FOR UPDATE，同套件并发写在此排队，后到者读到的是最新状态 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM CutKit k WHERE k.id = :id")
    Optional<CutKit> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT k FROM CutKit k "
            + "WHERE (:status IS NULL OR :status = '' OR k.status = :status) "
            + "  AND (:keyword IS NULL OR :keyword = '' "
            + "       OR k.kitNo LIKE CONCAT('%', :keyword, '%') "
            + "       OR k.orderNo LIKE CONCAT('%', :keyword, '%') "
            + "       OR k.cutBatchNo LIKE CONCAT('%', :keyword, '%') "
            + "       OR k.garmentName LIKE CONCAT('%', :keyword, '%')) "
            + "ORDER BY k.id DESC")
    Slice<CutKit> sliceBy(@Param("status") String status, @Param("keyword") String keyword, Pageable pageable);

    List<CutKit> findAllByOrderByIdDesc();

    boolean existsByKitNo(String kitNo);
}
