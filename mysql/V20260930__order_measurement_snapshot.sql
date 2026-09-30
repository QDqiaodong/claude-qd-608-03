-- ============================================================================
-- 订单量体快照 · 存量数据库升级脚本（MySQL 8.0）
-- 适用：已用旧版 schema 初始化、不方便重建镜像的环境。
-- 全新部署无需执行本脚本（schema.sql 已包含表结构与种子快照）。
--
-- 执行后：
--   1. measurement 增加乐观版本列；
--   2. 新建 order_measurement_snapshot；
--   3. 用订单当前指向的量体记录补齐历史快照；
--   4. 原量体记录已缺失的旧订单挂 PENDING_VERIFY，不伪造尺寸。
-- 幂等：可重复执行，已有快照的订单不会被覆盖（人工确认结果同样保留）。
-- ============================================================================

-- 1) 量体记录乐观版本列
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'measurement' AND COLUMN_NAME = 'version');
SET @ddl := IF(@col = 0,
    'ALTER TABLE measurement ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''乐观版本：并发编辑/下单竞争检测''',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 订单量体快照表
CREATE TABLE IF NOT EXISTS order_measurement_snapshot (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       BIGINT       NOT NULL COMMENT '所属订单（一单一份）',
    measurement_id BIGINT       COMMENT '来源量体记录（待核对为空）',
    record_no      VARCHAR(30)  COMMENT '冻结时量体单号',
    customer_name  VARCHAR(50)  COMMENT '客户姓名',
    tailor_name    VARCHAR(50)  COMMENT '量体师',
    measure_date   DATE         COMMENT '量体日期',
    bust           DECIMAL(6,2) COMMENT '胸围 cm（待核对为空，严禁伪造）',
    waist          DECIMAL(6,2) COMMENT '腰围 cm',
    hip            DECIMAL(6,2) COMMENT '臀围 cm',
    shoulder       DECIMAL(6,2) COMMENT '肩宽 cm',
    sleeve_len     DECIMAL(6,2) COMMENT '袖长 cm',
    body_len       DECIMAL(6,2) COMMENT '衣长 cm',
    pants_len      DECIMAL(6,2) COMMENT '裤长 cm',
    measure_remark VARCHAR(500)  COMMENT '量体备注',
    status         VARCHAR(20)  NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN/PENDING_VERIFY/MANUAL_CONFIRMED',
    source         VARCHAR(20)  NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN/BACKFILL',
    frozen_at      DATETIME     NOT NULL COMMENT '冻结时间',
    backfilled_at  DATETIME     COMMENT '补齐时间（旧订单）',
    verify_note    VARCHAR(500) COMMENT '待核对/核对说明',
    confirmed_by   VARCHAR(50)  COMMENT '人工核对操作人',
    confirmed_at   DATETIME     COMMENT '人工核对时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_snapshot (order_id),
    KEY idx_oms_measurement (measurement_id),
    KEY idx_oms_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单量体快照';

-- 3) 历史补齐 A：来源量体记录还在 → 完整冻结（已存在快照的订单跳过）
INSERT INTO order_measurement_snapshot
  (order_id, measurement_id, record_no, customer_name, tailor_name, measure_date,
   bust, waist, hip, shoulder, sleeve_len, body_len, pants_len, measure_remark,
   status, source, frozen_at, backfilled_at)
SELECT o.id, m.id, m.record_no, m.customer_name, m.tailor_name, m.measure_date,
       m.bust, m.waist, m.hip, m.shoulder, m.sleeve_len, m.body_len, m.pants_len, m.remark,
       'FROZEN', 'BACKFILL', NOW(3), NOW(3)
FROM custom_order o
JOIN measurement m ON m.id = o.measurement_id
WHERE NOT EXISTS (SELECT 1 FROM order_measurement_snapshot s WHERE s.order_id = o.id);

-- 4) 历史补齐 B：来源记录缺失 / 未绑定 → 待核对，尺寸留空，严禁伪造
INSERT INTO order_measurement_snapshot
  (order_id, measurement_id, status, source, frozen_at, backfilled_at, verify_note)
SELECT o.id, o.measurement_id, 'PENDING_VERIFY', 'BACKFILL', NOW(3), NOW(3),
       CASE WHEN o.measurement_id IS NULL
            THEN '历史订单未绑定量体记录，尺寸无法自动补齐，请人工核对'
            ELSE CONCAT('原量体记录（ID ', o.measurement_id, '）已删除，尺寸无法考证，严禁伪造，请人工核对')
       END
FROM custom_order o
WHERE NOT EXISTS (SELECT 1 FROM order_measurement_snapshot s WHERE s.order_id = o.id);

-- 结果核对（人工执行时查看）：应等于 custom_order 总数；PENDING_VERIFY 数即需人工核对的旧订单
-- SELECT COUNT(*) AS total_orders FROM custom_order;
-- SELECT COUNT(*) AS total_snapshots FROM order_measurement_snapshot;
-- SELECT status, COUNT(*) FROM order_measurement_snapshot GROUP BY status;
