package com.garment.factory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.garment.factory.domain.CutKitPiece;

public interface CutKitPieceRepository extends JpaRepository<CutKitPiece, Long> {

    List<CutKitPiece> findByKitIdAndPhaseOrderByIdAsc(Long kitId, String phase);

    List<CutKitPiece> findByKitIdOrderByPhaseAscIdAsc(Long kitId);

    long countByKitIdAndPhase(Long kitId, String phase);

    /** 同套件同阶段条码唯一：数据库层兜底重复扫码 */
    Optional<CutKitPiece> findByKitIdAndPhaseAndPieceCode(Long kitId, String phase, String pieceCode);

    void deleteByKitIdAndPhase(Long kitId, String phase);
}
