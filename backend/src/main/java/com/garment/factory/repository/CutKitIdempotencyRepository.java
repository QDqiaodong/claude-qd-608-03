package com.garment.factory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.garment.factory.domain.CutKitIdempotency;

import jakarta.persistence.LockModeType;

public interface CutKitIdempotencyRepository extends JpaRepository<CutKitIdempotency, Long> {

    Optional<CutKitIdempotency> findByIdemKey(String idemKey);

    /** 当前读（FOR UPDATE）：并发第二笔事务拿到套件行锁后，用它读到对方已提交的幂等记录做重放 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CutKitIdempotency r WHERE r.idemKey = :idemKey")
    Optional<CutKitIdempotency> findByIdemKeyForUpdate(@Param("idemKey") String idemKey);
}
