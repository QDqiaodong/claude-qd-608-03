-- 集成测试专用 H2（MySQL 兼容模式）建表：只建裁片套件模块用到的 5 张表，
-- 结构与主 schema.sql 保持一致；H2 不支持 MySQL 的 GENERATED 列，本模块也未使用。
CREATE TABLE IF NOT EXISTS cut_kit (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kit_no          VARCHAR(30)  NOT NULL UNIQUE,
    order_no        VARCHAR(30)  NOT NULL,
    cut_batch_no    VARCHAR(30)  NOT NULL,
    garment_name    VARCHAR(80)  NOT NULL,
    size_mark       VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    expected_total  INT          NOT NULL DEFAULT 0,
    sealed_at       TIMESTAMP NULL,
    sealed_by       VARCHAR(50) NULL,
    seal_no         VARCHAR(40) NULL,
    handed_over_at  TIMESTAMP NULL,
    handed_by       VARCHAR(50) NULL,
    receiver_name   VARCHAR(50) NULL,
    received_at     TIMESTAMP NULL,
    received_by     VARCHAR(50) NULL,
    exception_no    VARCHAR(30) NULL,
    exception_count INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(50)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    version         INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cut_kit_piece (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kit_id            BIGINT       NOT NULL,
    phase             VARCHAR(12)  NOT NULL,
    seq               INT          NOT NULL DEFAULT 0,
    piece_code        VARCHAR(60)  NULL,
    part              VARCHAR(30)  NOT NULL,
    side              VARCHAR(10)  NOT NULL DEFAULT 'CENTER',
    fabric_batch_no   VARCHAR(30)  NOT NULL,
    size_mark         VARCHAR(20)  NOT NULL,
    quantity          INT          NOT NULL DEFAULT 1,
    scanned_by        VARCHAR(50)  NULL,
    scanned_at        TIMESTAMP    NULL,
    CONSTRAINT uk_ckp_scan UNIQUE (kit_id, phase, piece_code)
);

CREATE TABLE IF NOT EXISTS cut_kit_exception (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exception_no        VARCHAR(30)  NOT NULL UNIQUE,
    kit_id              BIGINT       NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    discrepancies_json  CLOB         NOT NULL,
    raised_by           VARCHAR(50)  NOT NULL,
    raised_at           TIMESTAMP    NOT NULL,
    status              VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    resolved_by         VARCHAR(50)  NULL,
    resolved_at         TIMESTAMP    NULL,
    resolve_note        VARCHAR(500) NULL
);

CREATE TABLE IF NOT EXISTS cut_kit_idempotency (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idem_key      VARCHAR(80)  NOT NULL UNIQUE,
    kit_id        BIGINT       NOT NULL,
    action        VARCHAR(20)  NOT NULL,
    response_json CLOB         NOT NULL,
    http_status   INT          NOT NULL DEFAULT 200,
    created_at    TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS cut_kit_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kit_id        BIGINT       NOT NULL,
    event_type    VARCHAR(24)  NOT NULL,
    from_status   VARCHAR(20)  NULL,
    to_status     VARCHAR(20)  NULL,
    operator      VARCHAR(50)  NOT NULL,
    detail_json   CLOB         NULL,
    before_json   CLOB         NULL,
    after_json    CLOB         NULL,
    occurred_at   TIMESTAMP    NOT NULL
);
