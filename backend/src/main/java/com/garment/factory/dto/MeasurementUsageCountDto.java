package com.garment.factory.dto;

/**
 * 量体记录引用计数（量体列表批量标记用）：被多少未交付 / 已交付订单引用。
 */
public class MeasurementUsageCountDto {

    private final Long measurementId;
    private final Long activeCount;
    private final Long deliveredCount;

    public MeasurementUsageCountDto(Long measurementId, Long activeCount, Long deliveredCount) {
        this.measurementId = measurementId;
        this.activeCount = activeCount == null ? 0L : activeCount;
        this.deliveredCount = deliveredCount == null ? 0L : deliveredCount;
    }

    public Long getMeasurementId() {
        return measurementId;
    }

    public long getActiveCount() {
        return activeCount;
    }

    public long getDeliveredCount() {
        return deliveredCount;
    }
}
