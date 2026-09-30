package com.garment.factory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.garment.factory.domain.CutKitException;

public interface CutKitExceptionRepository extends JpaRepository<CutKitException, Long> {

    Optional<CutKitException> findByExceptionNo(String exceptionNo);

    List<CutKitException> findByKitIdOrderByIdDesc(Long kitId);

    boolean existsByKitIdAndStatus(Long kitId, String status);
}
