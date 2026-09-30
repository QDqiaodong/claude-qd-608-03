-- 集成测试专用 H2（MySQL 兼容模式）建表：裁片套件模块用到的 5 张表，
-- 结构与主 schema.sql 保持一致；H2 不支持 MySQL 的 GENERATED 列，本模块也未使用。

-- ---------- 订单量体快照模块测试所需基础表 ----------
CREATE TABLE IF NOT EXISTS customer (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_no  VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(50)  NOT NULL,
    phone        VARCHAR(20)  NOT NULL,
    gender       VARCHAR(10)  NOT NULL,
    vip_level    VARCHAR(20)  NOT NULL DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS style (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    style_no    VARCHAR(30)   NOT NULL UNIQUE,
    name        VARCHAR(80)   NOT NULL,
    category    VARCHAR(20)   NOT NULL,
    base_price  DECIMAL(10,2) NOT NULL,
    craft_level INT           NOT NULL,
    on_sale     TINYINT(1)    NOT NULL DEFAULT 1,
    remark      VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS fabric (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fabric_no    VARCHAR(30)   NOT NULL UNIQUE,
    name         VARCHAR(80)   NOT NULL,
    composition  VARCHAR(20)   NOT NULL,
    color        VARCHAR(30)   NOT NULL,
    color_hex    VARCHAR(10)   NOT NULL,
    stock_meters DECIMAL(10,2) NOT NULL,
    unit_price   DECIMAL(10,2) NOT NULL,
    supplier     VARCHAR(80)   NOT NULL
);

CREATE TABLE IF NOT EXISTS measurement (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_no     VARCHAR(30)  NOT NULL UNIQUE,
    customer_id   BIGINT       NOT NULL,
    customer_name VARCHAR(50)  NOT NULL,
    tailor_name   VARCHAR(50)  NOT NULL,
    measure_date  DATE         NOT NULL,
    bust          DECIMAL(6,2) NOT NULL,
    waist         DECIMAL(6,2) NOT NULL,
    hip           DECIMAL(6,2) NOT NULL,
    shoulder      DECIMAL(6,2) NOT NULL,
    sleeve_len    DECIMAL(6,2) NOT NULL,
    body_len      DECIMAL(6,2) NOT NULL,
    pants_len     DECIMAL(6,2) NOT NULL,
    remark        VARCHAR(500),
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS custom_order (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no       VARCHAR(30)   NOT NULL UNIQUE,
    customer_id    BIGINT        NOT NULL,
    style_id       BIGINT        NOT NULL,
    fabric_id      BIGINT        NOT NULL,
    measurement_id BIGINT        NULL,
    usage_meters   DECIMAL(10,2) NOT NULL,
    order_date     DATE          NOT NULL,
    delivery_date  DATE          NOT NULL,
    fabric_cost    DECIMAL(10,2) NOT NULL,
    craft_fee      DECIMAL(10,2) NOT NULL,
    total_price    DECIMAL(10,2) NOT NULL,
    deposit        DECIMAL(10,2) NOT NULL,
    balance        DECIMAL(10,2) NOT NULL,
    deposit_paid   TINYINT(1)    NOT NULL DEFAULT 0,
    balance_paid   TINYINT(1)    NOT NULL DEFAULT 0,
    status         VARCHAR(20)   NOT NULL,
    remark         VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS order_measurement_snapshot (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT       NOT NULL UNIQUE,
    measurement_id BIGINT       NULL,
    record_no      VARCHAR(30)  NULL,
    customer_id    BIGINT       NULL,
    customer_name  VARCHAR(50)  NULL,
    tailor_name    VARCHAR(50)  NULL,
    measure_date   DATE         NULL,
    bust           DECIMAL(6,2) NULL,
    waist          DECIMAL(6,2) NULL,
    hip            DECIMAL(6,2) NULL,
    shoulder       DECIMAL(6,2) NULL,
    sleeve_len     DECIMAL(6,2) NULL,
    body_len       DECIMAL(6,2) NULL,
    pants_len      DECIMAL(6,2) NULL,
    remark         VARCHAR(500) NULL,
    status         VARCHAR(20)  NOT NULL,
    frozen_at      TIMESTAMP    NOT NULL,
    confirmed_by   VARCHAR(50)  NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS work_order (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    wo_no         VARCHAR(30)  NOT NULL UNIQUE,
    order_id      BIGINT       NOT NULL,
    step          VARCHAR(20)  NOT NULL,
    owner         VARCHAR(50)  NOT NULL,
    planned_hours DECIMAL(8,2) NOT NULL,
    actual_hours  DECIMAL(8,2) NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL,
    remark        VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS fitting_issue (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_no         VARCHAR(30)  NOT NULL UNIQUE,
    order_id         BIGINT       NOT NULL,
    body_part        VARCHAR(30)  NOT NULL,
    severity         VARCHAR(20)  NOT NULL,
    description      VARCHAR(500) NOT NULL,
    responsible_step VARCHAR(20)  NOT NULL,
    raised_by        VARCHAR(50)  NOT NULL,
    raised_at        TIMESTAMP    NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    released         TINYINT(1)   NOT NULL DEFAULT 0,
    closed_at        TIMESTAMP    NULL,
    reopen_count     INT          NOT NULL DEFAULT 0,
    version          INT          NOT NULL DEFAULT 0,
    remark           VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS fitting_rework_round (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    round_no         VARCHAR(30)   NOT NULL UNIQUE,
    issue_id         BIGINT        NOT NULL,
    order_id         BIGINT        NOT NULL,
    round_seq        INT           NOT NULL,
    responsible_step VARCHAR(20)   NOT NULL,
    owner            VARCHAR(50)   NOT NULL,
    planned_hours    DECIMAL(8,2)  NOT NULL,
    actual_hours     DECIMAL(8,2)  NOT NULL DEFAULT 0,
    rework_reason    VARCHAR(500)  NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    started_at       TIMESTAMP     NOT NULL,
    finished_at      TIMESTAMP     NULL,
    reviewed_at      TIMESTAMP     NULL,
    reviewer         VARCHAR(50)   NULL,
    review_comment   VARCHAR(500)  NULL
);

CREATE TABLE IF NOT EXISTS fitting_action_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_id    BIGINT       NULL,
    order_id    BIGINT       NOT NULL,
    round_id    BIGINT       NULL,
    action      VARCHAR(30)  NOT NULL,
    operator    VARCHAR(50)  NOT NULL,
    from_status VARCHAR(20)  NULL,
    to_status   VARCHAR(20)  NULL,
    reason      VARCHAR(500) NULL,
    acted_at    TIMESTAMP    NOT NULL
);

-- ---------- 裁片套件 ----------
CREATE TABLE IF NOT EXISTS grade_scheme (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scheme_no      VARCHAR(30)  NOT NULL UNIQUE,
    name           VARCHAR(80)  NOT NULL,
    style_id       BIGINT       NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'SAVED',
    base_size_code VARCHAR(20)  NOT NULL,
    input_unit     VARCHAR(4)   NOT NULL DEFAULT 'CM',
    round_step_mm  DECIMAL(8,2) NOT NULL DEFAULT 1.00,
    sizes_json     CLOB         NOT NULL,
    bases_json     CLOB         NOT NULL,
    rules_json     CLOB         NOT NULL,
    snapshot_json  CLOB         NOT NULL,
    legacy_flag    TINYINT(1)   NOT NULL DEFAULT 0,
    result_version INT          NOT NULL DEFAULT 1,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

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
