-- ============================================================================
-- IDC 数据中心机房运维系统 · 建表 + 种子数据
-- 库名 idc_center，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 五个业务模块：
--   1. idc_room / idc_cabinet   机房 → 机柜（行/列、总 U 位数）
--   2. idc_server               服务器资产（机柜 + 起始 U 位 + 占几个 U + 状态）
--   3. idc_work_order           上架 / 下架 / 迁移工单（待审批→已批复→施工中→已完成）
--   4. idc_inspection           巡检记录（温湿度、电源、异常描述、是否处理）
--   5. idc_energy               能耗记录（抄表读数 → 用电量 → 电费）
--
-- 种子数据对照：
--   [M1] 机柜 U 位数差异        → C03 只有 32U，其余 42U
--   [M2] 服务器跨 U 位          → SRV-0006 / 0011 / 0021 / 0022 各占 4U
--   [M2] 状态着色               → REPAIR 橙（SRV-0004/0013/0028）、SCRAP 红（SRV-0005/0017/0030）
--   [M2] 下架不占 U 位          → SRV-0010 / SRV-0023 = OFFLINE
--   [M3] 工单五种状态齐活        → PENDING 3 / APPROVED 2 / WORKING 2 / DONE 5 / REJECTED 2
--   [M4] 巡检异常高亮           → 温度≥27℃、湿度不在 40~60%、电源 ABNORMAL 共 7 条
--   [M4] 异常未闭环             → 机柜 6 两条、机柜 9 一条 handled=0
--   [M5] 电费 = 用电量 × 电价   → 电价 0.68 元/kWh，2026-09 有 12 个机柜的抄表
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS idc_isolation_event;
DROP TABLE IF EXISTS idc_isolation_order;
DROP TABLE IF EXISTS idc_isolation_server;
DROP TABLE IF EXISTS idc_isolation;
DROP TABLE IF EXISTS idc_energy;
DROP TABLE IF EXISTS idc_inspection;
DROP TABLE IF EXISTS idc_work_order;
DROP TABLE IF EXISTS idc_server;
DROP TABLE IF EXISTS idc_cabinet;
DROP TABLE IF EXISTS idc_room;

CREATE TABLE idc_room (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(20)  NOT NULL COMMENT '机房编号',
    name           VARCHAR(50)  NOT NULL COMMENT '机房名称',
    floor_no       VARCHAR(10)           COMMENT '所在楼层',
    area_sqm       DECIMAL(8,2)          COMMENT '面积（平方米）',
    power_capacity DECIMAL(8,2)          COMMENT '设计总功率（kW）',
    duty_person    VARCHAR(30)           COMMENT '值班负责人',
    remark         VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='机房';

CREATE TABLE idc_cabinet (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    room_id     BIGINT                COMMENT '所属机房',
    code        VARCHAR(20)  NOT NULL COMMENT '机柜编号',
    name        VARCHAR(50)           COMMENT '机柜名称',
    row_no      INT                   COMMENT '行号',
    col_no      INT                   COMMENT '列号',
    total_u     INT                   COMMENT '总 U 位数',
    rated_power DECIMAL(8,2)          COMMENT '额定功率（kW）',
    remark      VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cabinet_code (code),
    KEY idx_cabinet_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='机柜';

CREATE TABLE idc_server (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    asset_no   VARCHAR(30)  NOT NULL COMMENT '资产编号',
    brand      VARCHAR(30)           COMMENT '品牌',
    model      VARCHAR(40)           COMMENT '型号',
    serial_no  VARCHAR(60)           COMMENT '序列号',
    cpu_model  VARCHAR(60)           COMMENT 'CPU 型号',
    memory_gb  INT                   COMMENT '内存（GB）',
    disk_spec  VARCHAR(80)           COMMENT '硬盘配置',
    ip_address VARCHAR(40)           COMMENT '管理 IP',
    os_version VARCHAR(40)           COMMENT '操作系统',
    cabinet_id BIGINT                COMMENT '所属机柜',
    start_u    INT                   COMMENT '起始 U 位',
    size_u     INT                   COMMENT '占用 U 数',
    status     VARCHAR(20)           COMMENT 'ONLINE 在架 / OFFLINE 下架 / REPAIR 维修 / SCRAP 报废',
    owner_name VARCHAR(50)           COMMENT '归属业务系统',
    buy_date   DATE                  COMMENT '上架日期',
    remark     VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_asset_no (asset_no),
    KEY idx_server_cabinet (cabinet_id),
    KEY idx_server_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务器资产';

CREATE TABLE idc_work_order (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(30) NOT NULL COMMENT '工单号',
    order_type    VARCHAR(20)          COMMENT 'ON 上架 / OFF 下架 / MOVE 迁移',
    server_id     BIGINT               COMMENT '关联服务器',
    asset_no      VARCHAR(30)          COMMENT '资产编号快照',
    cabinet_id    BIGINT               COMMENT '目标机柜（下架时为下架前机柜，见 src_* 快照）',
    target_u      INT                  COMMENT '目标起始 U 位（下架时为下架前 U 位）',
    src_cabinet_id BIGINT              COMMENT '历史位置快照：作业发起时所在机柜（不随资产当前位置变化）',
    src_start_u   INT                  COMMENT '历史位置快照：作业发起时起始 U 位',
    applicant     VARCHAR(30)          COMMENT '申请人',
    apply_time    DATETIME             COMMENT '申请时间',
    status        VARCHAR(20)          COMMENT 'PENDING/APPROVED/WORKING/DONE/REJECTED',
    approver      VARCHAR(30)          COMMENT '审批人',
    handler       VARCHAR(30)          COMMENT '施工人',
    finish_time   DATETIME             COMMENT '完成时间',
    reject_reason VARCHAR(200)         COMMENT '驳回原因',
    remark        VARCHAR(200)         COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_status (status),
    KEY idx_order_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上架下架工单';

CREATE TABLE idc_inspection (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    cabinet_id   BIGINT                 COMMENT '巡检机柜',
    inspector    VARCHAR(30)            COMMENT '巡检人',
    inspect_time DATETIME               COMMENT '巡检时间',
    temperature  DECIMAL(5,2)           COMMENT '温度（℃）',
    humidity     DECIMAL(5,2)           COMMENT '湿度（%）',
    power_status VARCHAR(20)            COMMENT 'NORMAL 正常 / ABNORMAL 异常',
    abnormal     INT                    COMMENT '是否异常：1 是 / 0 否',
    description  VARCHAR(300)           COMMENT '异常描述',
    handled      INT                    COMMENT '是否处理：1 是 / 0 否',
    handle_note  VARCHAR(200)           COMMENT '处理说明',
    handle_time  DATETIME               COMMENT '处理时间',
    PRIMARY KEY (id),
    KEY idx_insp_cabinet (cabinet_id),
    KEY idx_insp_time (inspect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='巡检记录';

CREATE TABLE idc_energy (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    cabinet_id   BIGINT                 COMMENT '机柜',
    meter_date   DATE                   COMMENT '抄表日期',
    meter_reading DECIMAL(12,2)         COMMENT '电表读数（kWh，累计）',
    usage_kwh    DECIMAL(12,2)          COMMENT '本期用电量（kWh）',
    unit_price   DECIMAL(6,3)           COMMENT '电价（元/kWh）',
    fee          DECIMAL(12,2)          COMMENT '电费（元）',
    meter_reader VARCHAR(30)            COMMENT '抄表人',
    remark       VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_energy_cabinet (cabinet_id),
    KEY idx_energy_date (meter_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能耗记录';

-- ============================================================================
-- 模块六：机柜停电检修隔离
--   idc_isolation         隔离主记录（待生效 → 隔离中 → 已解除 / 已取消）
--   idc_isolation_server  生效那一刻固定的受影响服务器快照（在架 / 维修中）
--   idc_isolation_order   生效那一刻被冻结的上架 / 迁移施工快照
--   idc_isolation_event   操作留痕（创建 / 生效 / 生效失败 / 取消 / 解除）
-- ============================================================================

CREATE TABLE idc_isolation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    isolation_no     VARCHAR(30)  NOT NULL COMMENT '隔离单号',
    cabinet_id       BIGINT       NOT NULL COMMENT '隔离机柜',
    plan_start       DATETIME     NOT NULL COMMENT '计划开始时间',
    plan_end         DATETIME     NOT NULL COMMENT '计划结束时间',
    reason           VARCHAR(300) NOT NULL COMMENT '检修原因',
    initiator        VARCHAR(30)  NOT NULL COMMENT '发起人',
    manager          VARCHAR(30)  NOT NULL COMMENT '现场负责人',
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING 待生效 / ACTIVE 隔离中 / RELEASED 已解除 / CANCELED 已取消',
    active_cabinet   BIGINT                COMMENT '仅隔离中 = 机柜ID，唯一索引保证同机柜任一时刻只有一条隔离中',
    created_at       DATETIME              COMMENT '创建时间',
    activated_at     DATETIME              COMMENT '生效时间',
    released_at      DATETIME              COMMENT '解除时间',
    release_operator VARCHAR(30)           COMMENT '解除操作人（须为现场负责人）',
    release_note     VARCHAR(300)          COMMENT '解除说明',
    canceled_at      DATETIME              COMMENT '取消时间',
    cancel_operator  VARCHAR(30)           COMMENT '取消操作人',
    cancel_reason    VARCHAR(300)          COMMENT '取消原因',
    PRIMARY KEY (id),
    UNIQUE KEY uk_isolation_no (isolation_no),
    UNIQUE KEY uk_isolation_active (active_cabinet),
    KEY idx_isolation_cabinet (cabinet_id),
    KEY idx_isolation_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='机柜停电检修隔离';

CREATE TABLE idc_isolation_server (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    isolation_id BIGINT      NOT NULL COMMENT '隔离记录',
    server_id    BIGINT      NOT NULL COMMENT '服务器',
    asset_no     VARCHAR(30)           COMMENT '资产编号快照',
    brand        VARCHAR(30)           COMMENT '品牌快照',
    model        VARCHAR(40)           COMMENT '型号快照',
    start_u      INT                   COMMENT '起始 U 位快照',
    size_u       INT                   COMMENT '占用 U 数快照',
    owner_name   VARCHAR(50)           COMMENT '业务归属快照',
    status       VARCHAR(20)           COMMENT '生效时状态快照（ONLINE 在架 / REPAIR 维修）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iso_server (isolation_id, server_id),
    KEY idx_iso_server_iso (isolation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='隔离影响服务器快照';

CREATE TABLE idc_isolation_order (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    isolation_id     BIGINT      NOT NULL COMMENT '隔离记录',
    order_id         BIGINT      NOT NULL COMMENT '工单',
    order_no         VARCHAR(30)           COMMENT '工单号快照',
    order_type       VARCHAR(20)           COMMENT '工单类型快照（ON 上架 / MOVE 迁移）',
    asset_no         VARCHAR(30)           COMMENT '资产编号快照',
    target_u         INT                   COMMENT '目标 U 位快照',
    applicant        VARCHAR(30)           COMMENT '申请人快照',
    status_at_freeze VARCHAR(20)           COMMENT '冻结时状态（PENDING 待审批 / APPROVED 已批复）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iso_order (isolation_id, order_id),
    KEY idx_iso_order_iso (isolation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='隔离冻结施工快照';

CREATE TABLE idc_isolation_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    isolation_id BIGINT       NOT NULL COMMENT '隔离记录',
    action       VARCHAR(20)  NOT NULL COMMENT 'CREATE 创建 / ACTIVATE 生效 / ACTIVATE_FAIL 生效失败 / CANCEL 取消 / RELEASE 解除',
    operator     VARCHAR(30)           COMMENT '操作人',
    detail       VARCHAR(500)          COMMENT '操作说明',
    created_at   DATETIME              COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_iso_event (isolation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='隔离操作记录';


-- ---------------------------- 机房 ----------------------------
INSERT INTO idc_room (id, code, name, floor_no, area_sqm, power_capacity, duty_person, remark) VALUES
    (1, 'IDC-A', '主机房 A', '2F', 420.00, 320.00, '张伟', '核心交易区，双路市电 + 柴发'),
    (2, 'IDC-B', '主机房 B', '3F', 380.00, 280.00, '李娜', '大数据与存储区'),
    (3, 'IDC-C', '边缘机房 C', '3F', 160.00, 120.00, '王强', '边缘计算节点，单路市电'),
    (4, 'IDC-D', '备件机房 D', '1F', 90.00, 60.00, '赵敏', '备件与待报废设备暂存');

-- ---------------------------- 机柜 ----------------------------
INSERT INTO idc_cabinet (id, room_id, code, name, row_no, col_no, total_u, rated_power, remark) VALUES
    (1, 1, 'A01', 'A 区 01 号柜', 1, 1, 42, 8.00, '核心交易区，冷热通道封闭'),
    (2, 1, 'A02', 'A 区 02 号柜', 1, 2, 42, 8.00, '核心交易区'),
    (3, 1, 'A03', 'A 区 03 号柜', 1, 3, 42, 8.00, '核心交易区'),
    (4, 2, 'B01', 'B 区 01 号柜', 1, 1, 42, 7.50, '大数据区'),
    (5, 2, 'B02', 'B 区 02 号柜', 1, 2, 42, 7.50, '大数据区'),
    (6, 2, 'B03', 'B 区 03 号柜', 2, 1, 42, 9.00, 'GPU 训练区，高密度'),
    (7, 3, 'C01', 'C 区 01 号柜', 1, 1, 42, 5.00, '边缘节点'),
    (8, 3, 'C02', 'C 区 02 号柜', 1, 2, 42, 5.00, '边缘节点'),
    (9, 3, 'C03', 'C 区 03 号柜', 2, 1, 32, 4.00, '矮柜，32U'),
    (10, 4, 'D01', 'D 区 01 号柜', 1, 1, 42, 4.00, '待修件暂存'),
    (11, 4, 'D02', 'D 区 02 号柜', 1, 2, 42, 4.00, '备件上架'),
    (12, 4, 'D03', 'D 区 03 号柜', 2, 1, 42, 4.00, '报废待拆解');

-- ---------------------------- 服务器资产 ----------------------------
INSERT INTO idc_server (id, asset_no, brand, model, serial_no, cpu_model, memory_gb, disk_spec,
                        ip_address, os_version, cabinet_id, start_u, size_u, status, owner_name, buy_date, remark) VALUES
    (1, 'SRV-0001', '戴尔', 'PowerEdge R750', 'SN-DL-0001', 'Xeon 4314 ×2', 64, '2×960G SSD + 4×4T',
     '10.10.1.11', 'CentOS 7.9', 1, 1, 2, 'ONLINE', '核心交易系统', '2024-03-12', NULL),
    (2, 'SRV-0002', '戴尔', 'PowerEdge R750', 'SN-DL-0002', 'Xeon 4314 ×2', 64, '2×960G SSD + 4×4T',
     '10.10.1.12', 'CentOS 7.9', 1, 4, 2, 'ONLINE', '核心交易系统', '2024-03-12', NULL),
    (3, 'SRV-0003', '华为', '2288H V5', 'SN-HW-0003', 'Xeon 4210 ×2', 32, '2×480G SSD',
     '10.10.1.13', 'openEuler 22.03', 1, 7, 1, 'ONLINE', '支付网关', '2023-11-08', NULL),
    (4, 'SRV-0004', '华为', '2288H V5', 'SN-HW-0004', 'Xeon 4210 ×2', 32, '2×480G SSD',
     '10.10.1.14', 'openEuler 22.03', 1, 9, 1, 'REPAIR', '支付网关', '2023-11-08', NULL),
    (5, 'SRV-0005', '联想', 'ThinkSystem SR650', 'SN-LX-0005', 'Xeon 4208 ×2', 16, '2×600G SAS',
     '10.10.1.15', 'Windows Server 2019', 1, 11, 1, 'SCRAP', '内部 OA', '2021-06-21', NULL),
    (6, 'SRV-0006', '浪潮', 'NF5280M6', 'SN-LC-0006', 'Xeon 6330 ×2', 128, '2×960G SSD + 8×8T',
     '10.10.1.16', 'CentOS 7.9', 1, 13, 4, 'ONLINE', '数据中台', '2024-07-03', NULL),
    (7, 'SRV-0007', '新华三', 'UniServer R4900', 'SN-H3-0007', 'Xeon 5320 ×2', 64, '2×960G SSD',
     '10.10.2.21', 'CentOS 7.9', 2, 1, 2, 'ONLINE', '用户中心', '2024-01-19', NULL),
    (8, 'SRV-0008', '新华三', 'UniServer R4900', 'SN-H3-0008', 'Xeon 5320 ×2', 64, '2×960G SSD',
     '10.10.2.22', 'CentOS 7.9', 2, 4, 2, 'ONLINE', '用户中心', '2024-01-19', NULL),
    (9, 'SRV-0009', '戴尔', 'PowerEdge R650', 'SN-DL-0009', 'Xeon 4309Y ×2', 32, '2×480G SSD',
     '10.10.2.23', 'Ubuntu 22.04', 2, 7, 1, 'ONLINE', '风控引擎', '2024-05-06', NULL),
    (10, 'SRV-0010', '华为', '2288H V5', 'SN-HW-0010', 'Xeon 4210 ×2', 32, '2×480G SSD',
     '10.10.2.24', 'openEuler 22.03', 2, 9, 1, 'OFFLINE', '风控引擎（已下架）', '2022-09-15', NULL),
    (11, 'SRV-0011', '浪潮', 'NF5280M6', 'SN-LC-0011', 'Xeon 6330 ×2', 256, '2×960G SSD + 12×8T',
     '10.10.2.25', 'CentOS 7.9', 3, 1, 4, 'ONLINE', '对象存储', '2024-08-11', NULL),
    (12, 'SRV-0012', '浪潮', 'NF5280M6', 'SN-LC-0012', 'Xeon 6330 ×2', 128, '2×960G SSD + 8×8T',
     '10.10.2.26', 'CentOS 7.9', 3, 6, 1, 'ONLINE', '对象存储', '2024-08-11', NULL),
    (13, 'SRV-0013', '联想', 'ThinkSystem SR650', 'SN-LX-0013', 'Xeon 4208 ×2', 16, '2×600G SAS',
     '10.10.2.27', 'Windows Server 2019', 3, 8, 1, 'REPAIR', '内部 OA', '2021-06-21', NULL),
    (14, 'SRV-0014', '华为', 'TaiShan 2280', 'SN-HW-0014', '鲲鹏 920 ×2', 128, '2×960G SSD',
     '10.10.3.31', 'openEuler 22.03', 4, 1, 2, 'ONLINE', '国产化试点', '2024-04-02', NULL),
    (15, 'SRV-0015', '华为', 'TaiShan 2280', 'SN-HW-0015', '鲲鹏 920 ×2', 128, '2×960G SSD',
     '10.10.3.32', 'openEuler 22.03', 4, 4, 2, 'ONLINE', '国产化试点', '2024-04-02', NULL),
    (16, 'SRV-0016', '戴尔', 'PowerEdge R750xs', 'SN-DL-0016', 'Xeon 5315Y ×2', 64, '2×960G SSD + 6×6T',
     '10.10.3.33', 'CentOS 7.9', 4, 8, 1, 'ONLINE', '数仓计算', '2024-06-18', NULL),
    (17, 'SRV-0017', '戴尔', 'PowerEdge R750xs', 'SN-DL-0017', 'Xeon 5315Y ×2', 64, '2×960G SSD + 6×6T',
     '10.10.3.34', 'CentOS 7.9', 4, 10, 1, 'SCRAP', '数仓计算（待拆解）', '2022-02-09', NULL),
    (18, 'SRV-0018', '新华三', 'UniServer R4700', 'SN-H3-0018', 'Xeon 4310 ×2', 32, '2×480G SSD',
     '10.10.3.41', 'Ubuntu 22.04', 5, 1, 1, 'ONLINE', '消息队列', '2024-02-27', NULL),
    (19, 'SRV-0019', '新华三', 'UniServer R4700', 'SN-H3-0019', 'Xeon 4310 ×2', 32, '2×480G SSD',
     '10.10.3.42', 'Ubuntu 22.04', 5, 3, 1, 'ONLINE', '消息队列', '2024-02-27', NULL),
    (20, 'SRV-0020', '联想', 'ThinkSystem SR590', 'SN-LX-0020', 'Xeon 4214 ×2', 64, '2×960G SSD',
     '10.10.3.43', 'CentOS 7.9', 5, 5, 2, 'ONLINE', '日志平台', '2023-12-05', NULL),
    (21, 'SRV-0021', '浪潮', 'NF5468M6', 'SN-LC-0021', 'Xeon 6348 ×2', 512, '2×960G SSD + 8×3.84T',
     '10.10.3.51', 'Ubuntu 22.04', 6, 1, 4, 'ONLINE', 'AI 训练（8 卡 GPU）', '2025-01-16', NULL),
    (22, 'SRV-0022', '浪潮', 'NF5468M6', 'SN-LC-0022', 'Xeon 6348 ×2', 512, '2×960G SSD + 8×3.84T',
     '10.10.3.52', 'Ubuntu 22.04', 6, 6, 4, 'ONLINE', 'AI 训练（8 卡 GPU）', '2025-01-16', NULL),
    (23, 'SRV-0023', '华为', '2288H V5', 'SN-HW-0023', 'Xeon 4210 ×2', 32, '2×480G SSD',
     '10.10.3.53', 'openEuler 22.03', 6, 11, 1, 'OFFLINE', 'AI 推理（已下架）', '2022-07-22', NULL),
    (24, 'SRV-0024', '戴尔', 'PowerEdge R640', 'SN-DL-0024', 'Xeon 4214R ×2', 32, '2×480G SSD',
     '10.10.4.61', 'CentOS 7.9', 7, 1, 1, 'ONLINE', '边缘缓存', '2023-08-30', NULL),
    (25, 'SRV-0025', '戴尔', 'PowerEdge R640', 'SN-DL-0025', 'Xeon 4214R ×2', 32, '2×480G SSD',
     '10.10.4.62', 'CentOS 7.9', 7, 3, 1, 'ONLINE', '边缘缓存', '2023-08-30', NULL),
    (26, 'SRV-0026', '联想', 'ThinkSystem SR650', 'SN-LX-0026', 'Xeon 4208 ×2', 16, '2×600G SAS',
     '10.10.4.71', 'Windows Server 2019', 8, 1, 2, 'ONLINE', '边缘网关', '2023-05-11', NULL),
    (27, 'SRV-0027', '新华三', 'UniServer R2900', 'SN-H3-0027', 'Xeon 4310 ×2', 32, '2×480G SSD',
     '10.10.4.81', 'Ubuntu 22.04', 9, 1, 1, 'ONLINE', '监控采集', '2024-09-09', NULL),
    (28, 'SRV-0028', '华为', '2288H V5', 'SN-HW-0028', 'Xeon 4210 ×2', 32, '2×480G SSD',
     '10.10.5.91', 'openEuler 22.03', 10, 1, 1, 'REPAIR', '待修件（主板故障）', '2021-10-14', NULL),
    (29, 'SRV-0029', '戴尔', 'PowerEdge R740', 'SN-DL-0029', 'Xeon 4214 ×2', 64, '2×960G SSD',
     '10.10.5.101', 'CentOS 7.9', 11, 1, 2, 'ONLINE', '备件池', '2022-11-25', NULL),
    (30, 'SRV-0030', '浪潮', 'NF5270M6', 'SN-LC-0030', 'Xeon 4310 ×2', 32, '2×480G SSD',
     '10.10.5.111', 'CentOS 7.9', 12, 1, 1, 'SCRAP', '待报废拆解', '2020-04-17', NULL);

-- ---------------------------- 上架下架工单 ----------------------------
-- src_cabinet_id / src_start_u 为【作业前位置】永久快照：
--   OFF 固定下架前机柜/U（与 cabinet_id/target_u 同值）；MOVE 固定迁移前机柜/U；ON 为空。
-- 完成下架只释放资产当前位置，不改动这里，故已完成 OFF 单永远能查到下架前位置。
INSERT INTO idc_work_order (id, order_no, order_type, server_id, asset_no, cabinet_id, target_u,
                            src_cabinet_id, src_start_u,
                            applicant, apply_time, status, approver, handler, finish_time, reject_reason, remark) VALUES
    (1, 'WO20260801001', 'ON', 10, 'SRV-0010', 2, 12,
     NULL, NULL,
     '孙浩', '2026-08-01 09:12:00', 'DONE', '值班主管', '运维组', '2026-08-02 16:40:00', NULL, '业务扩容，重新上架'),
    (2, 'WO20260805002', 'OFF', 19, 'SRV-0019', 5, 3,
     5, 3,
     '孙浩', '2026-08-05 10:05:00', 'DONE', '值班主管', '运维组', '2026-08-06 11:20:00', NULL, '推理业务下线'),
    (3, 'WO20260812003', 'MOVE', 3, 'SRV-0003', 1, 20,
     1, 7,
     '周琳', '2026-08-12 14:30:00', 'DONE', '值班主管', '运维组', '2026-08-13 09:50:00', NULL, '迁移到 A01 高位散热更好'),
    (4, 'WO20260818004', 'ON', 27, 'SRV-0027', 9, 5,
     NULL, NULL,
     '周琳', '2026-08-18 08:45:00', 'DONE', '值班主管', '运维组', '2026-08-19 15:05:00', NULL, '监控采集节点扩容'),
    (5, 'WO20260824005', 'OFF', 5, 'SRV-0005', 1, 11,
     1, 11,
     '孙浩', '2026-08-24 16:22:00', 'DONE', '值班主管', '运维组', '2026-08-25 10:15:00', NULL, '报废下架，腾 U 位'),
    (6, 'WO20260901006', 'ON', 29, 'SRV-0029', 11, 20,
     NULL, NULL,
     '陈锋', '2026-09-01 09:00:00', 'WORKING', '值班主管', '运维组', NULL, NULL, '备件池上架到 D02 高位'),
    (7, 'WO20260903007', 'MOVE', 12, 'SRV-0012', 3, 12,
     3, 6,
     '陈锋', '2026-09-03 13:40:00', 'WORKING', '值班主管', '运维组', NULL, NULL, '与存储柜并柜'),
    (8, 'WO20260908008', 'OFF', 28, 'SRV-0028', 10, 1,
     10, 1,
     '周琳', '2026-09-08 11:05:00', 'APPROVED', '值班主管', NULL, NULL, NULL, '主板报废，拆件后下架'),
    (9, 'WO20260910009', 'ON', 24, 'SRV-0024', 7, 10,
     NULL, NULL,
     '孙浩', '2026-09-10 15:35:00', 'APPROVED', '值班主管', NULL, NULL, NULL, '边缘缓存扩容'),
    (10, 'WO20260915010', 'MOVE', 16, 'SRV-0016', 4, 20,
     4, 8,
     '陈锋', '2026-09-15 10:20:00', 'PENDING', NULL, NULL, NULL, NULL, '避开高热区'),
    (11, 'WO20260918011', 'OFF', 17, 'SRV-0017', 4, 10,
     4, 10,
     '周琳', '2026-09-18 09:48:00', 'PENDING', NULL, NULL, NULL, NULL, '已报废，申请下架拆解'),
    (12, 'WO20260920012', 'ON', 30, 'SRV-0030', 12, 15,
     NULL, NULL,
     '孙浩', '2026-09-20 14:10:00', 'PENDING', NULL, NULL, NULL, NULL, '待报废件重新评估'),
    (13, 'WO20260905013', 'MOVE', 9, 'SRV-0009', 2, 30,
     2, 7,
     '陈锋', '2026-09-05 09:30:00', 'REJECTED', NULL, NULL, NULL, '目标 U 位与散热风道冲突，改期', '驳回后重新排期'),
    (14, 'WO20260911014', 'OFF', 26, 'SRV-0026', 8, 1,
     8, 1,
     '周琳', '2026-09-11 16:05:00', 'REJECTED', NULL, NULL, NULL, '边缘网关仍在服务，不允许下架', '业务方不同意');

-- ---------------------------- 巡检记录 ----------------------------
INSERT INTO idc_inspection (id, cabinet_id, inspector, inspect_time, temperature, humidity,
                            power_status, abnormal, description, handled, handle_note, handle_time) VALUES
    (1, 1, '刘岩', '2026-09-21 09:10:00', 22.50, 48.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (2, 1, '刘岩', '2026-09-14 09:05:00', 23.10, 47.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (3, 2, '刘岩', '2026-09-21 09:22:00', 24.80, 45.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (4, 3, '刘岩', '2026-09-21 09:35:00', 28.60, 52.00,
     'NORMAL', 1, 'A03 柜顶部温度偏高，出风口有积灰', 1, '清理滤网，调整盲板', '2026-09-21 15:20:00'),
    (5, 4, '马涛', '2026-09-21 10:02:00', 23.40, 49.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (6, 5, '马涛', '2026-09-20 10:15:00', 26.90, 38.00,
     'NORMAL', 1, 'B02 湿度低于下限，加湿器水箱见底', 1, '补水并复位加湿器', '2026-09-20 14:05:00'),
    (7, 6, '马涛', '2026-09-21 10:30:00', 31.20, 41.00,
     'NORMAL', 1, 'GPU 柜负载高，回风温度 31.2℃', 0, NULL, NULL),
    (8, 6, '马涛', '2026-09-18 10:40:00', 30.80, 43.00,
     'NORMAL', 1, 'GPU 柜持续高温，已限频', 0, NULL, NULL),
    (9, 7, '孙浩', '2026-09-21 11:00:00', 24.10, 50.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (10, 8, '孙浩', '2026-09-19 11:12:00', 25.60, 46.00,
     'NORMAL', 0, '各项指标正常', 1, NULL, NULL),
    (11, 9, '孙浩', '2026-09-21 11:25:00', 27.50, 44.00,
     'ABNORMAL', 1, 'C03 柜 PDU B 路空开跳闸，单路供电', 0, NULL, NULL),
    (12, 10, '陈锋', '2026-09-21 14:05:00', 22.80, 51.00,
     'NORMAL', 0, '备件柜正常', 1, NULL, NULL),
    (13, 11, '陈锋', '2026-09-17 14:20:00', 23.90, 47.00,
     'NORMAL', 0, '备件柜正常', 1, NULL, NULL),
    (14, 12, '陈锋', '2026-09-21 14:35:00', 21.50, 55.00,
     'NORMAL', 0, '报废件暂存柜正常', 1, NULL, NULL),
    (15, 1, '刘岩', '2026-09-07 09:00:00', 23.00, 49.00,
     'NORMAL', 0, '周巡检正常', 1, NULL, NULL),
    (16, 2, '刘岩', '2026-09-07 09:18:00', 29.40, 45.00,
     'NORMAL', 1, 'A02 中层温度异常，风扇模块告警', 1, '更换风扇模块', '2026-09-07 17:10:00'),
    (17, 4, '马涛', '2026-09-07 10:05:00', 22.20, 62.00,
     'NORMAL', 1, 'B01 湿度高于上限，冷凝水盘积水', 1, '疏通排水管', '2026-09-07 16:30:00'),
    (18, 5, '马涛', '2026-09-02 10:30:00', 23.70, 48.00,
     'NORMAL', 0, '周巡检正常', 1, NULL, NULL);

-- ---------------------------- 能耗记录 ----------------------------
INSERT INTO idc_energy (id, cabinet_id, meter_date, meter_reading, usage_kwh, unit_price, fee, meter_reader, remark) VALUES
    (1, 1, '2026-07-31', 18240.00, 0.00, 0.68, 0.00, '马涛', '首期建账，无上期读数'),
    (2, 1, '2026-08-31', 19420.00, 1180.00, 0.68, 802.40, '马涛', '2026-08 月度抄表'),
    (3, 1, '2026-09-20', 20600.00, 1180.00, 0.68, 802.40, '马涛', '2026-09 月度抄表'),
    (4, 2, '2026-07-31', 15420.00, 0.00, 0.68, 0.00, '孙浩', '首期建账，无上期读数'),
    (5, 2, '2026-08-31', 16462.00, 1042.00, 0.68, 708.56, '孙浩', '2026-08 月度抄表'),
    (6, 2, '2026-09-20', 17504.00, 1042.00, 0.68, 708.56, '孙浩', '2026-09 月度抄表'),
    (7, 3, '2026-07-31', 21130.00, 0.00, 0.68, 0.00, '陈锋', '首期建账，无上期读数'),
    (8, 3, '2026-08-31', 22488.00, 1358.00, 0.68, 923.44, '陈锋', '2026-08 月度抄表'),
    (9, 3, '2026-09-20', 23846.00, 1358.00, 0.68, 923.44, '陈锋', '2026-09 月度抄表'),
    (10, 4, '2026-07-31', 17680.00, 0.00, 0.68, 0.00, '刘岩', '首期建账，无上期读数'),
    (11, 4, '2026-08-31', 18894.00, 1214.00, 0.68, 825.52, '刘岩', '2026-08 月度抄表'),
    (12, 4, '2026-09-20', 20108.00, 1214.00, 0.68, 825.52, '刘岩', '2026-09 月度抄表'),
    (13, 5, '2026-07-31', 14320.00, 0.00, 0.68, 0.00, '马涛', '首期建账，无上期读数'),
    (14, 5, '2026-08-31', 15296.00, 976.00, 0.68, 663.68, '马涛', '2026-08 月度抄表'),
    (15, 5, '2026-09-20', 16272.00, 976.00, 0.68, 663.68, '马涛', '2026-09 月度抄表'),
    (16, 6, '2026-07-31', 26890.00, 0.00, 0.68, 0.00, '孙浩', '首期建账，无上期读数'),
    (17, 6, '2026-08-31', 29066.00, 2176.00, 0.68, 1479.68, '孙浩', '2026-08 月度抄表'),
    (18, 6, '2026-09-20', 31242.00, 2176.00, 0.68, 1479.68, '孙浩', '2026-09 月度抄表'),
    (19, 7, '2026-07-31', 9210.00, 0.00, 0.68, 0.00, '陈锋', '首期建账，无上期读数'),
    (20, 7, '2026-08-31', 9822.00, 612.00, 0.68, 416.16, '陈锋', '2026-08 月度抄表'),
    (21, 7, '2026-09-20', 10434.00, 612.00, 0.68, 416.16, '陈锋', '2026-09 月度抄表'),
    (22, 8, '2026-07-31', 8670.00, 0.00, 0.68, 0.00, '刘岩', '首期建账，无上期读数'),
    (23, 8, '2026-08-31', 9244.00, 574.00, 0.68, 390.32, '刘岩', '2026-08 月度抄表'),
    (24, 8, '2026-09-20', 9818.00, 574.00, 0.68, 390.32, '刘岩', '2026-09 月度抄表'),
    (25, 9, '2026-07-31', 5240.00, 0.00, 0.68, 0.00, '马涛', '首期建账，无上期读数'),
    (26, 9, '2026-08-31', 5588.00, 348.00, 0.68, 236.64, '马涛', '2026-08 月度抄表'),
    (27, 9, '2026-09-20', 5936.00, 348.00, 0.68, 236.64, '马涛', '2026-09 月度抄表'),
    (28, 10, '2026-07-31', 3180.00, 0.00, 0.68, 0.00, '孙浩', '首期建账，无上期读数'),
    (29, 10, '2026-08-31', 3356.00, 176.00, 0.68, 119.68, '孙浩', '2026-08 月度抄表'),
    (30, 10, '2026-09-20', 3532.00, 176.00, 0.68, 119.68, '孙浩', '2026-09 月度抄表'),
    (31, 11, '2026-07-31', 7420.00, 0.00, 0.68, 0.00, '陈锋', '首期建账，无上期读数'),
    (32, 11, '2026-08-31', 7888.00, 468.00, 0.68, 318.24, '陈锋', '2026-08 月度抄表'),
    (33, 11, '2026-09-20', 8356.00, 468.00, 0.68, 318.24, '陈锋', '2026-09 月度抄表'),
    (34, 12, '2026-07-31', 2960.00, 0.00, 0.68, 0.00, '刘岩', '首期建账，无上期读数'),
    (35, 12, '2026-08-31', 3092.00, 132.00, 0.68, 89.76, '刘岩', '2026-08 月度抄表'),
    (36, 12, '2026-09-20', 3224.00, 132.00, 0.68, 89.76, '刘岩', '2026-09 月度抄表');

-- ---------------------------- 停电检修隔离 ----------------------------
-- 历史已解除：B01 柜 UPS 电池组更换（生效时固定 3 台在架服务器，冻结 1 张迁移工单）
INSERT INTO idc_isolation (id, isolation_no, cabinet_id, plan_start, plan_end, reason, initiator, manager,
                           status, active_cabinet, created_at, activated_at, released_at,
                           release_operator, release_note, canceled_at, cancel_operator, cancel_reason) VALUES
    (1, 'ISO20260915001', 4, '2026-09-16 08:00:00', '2026-09-16 12:00:00',
     'B01 柜 UPS 电池组更换，需全柜断电检修', '马涛', '马涛',
     'RELEASED', NULL, '2026-09-15 17:30:00', '2026-09-16 08:05:00', '2026-09-16 11:42:00',
     '马涛', '电池组更换完成，双路供电恢复正常，巡检复核无异常', NULL, NULL, NULL),
    (2, 'ISO20260926001', 12, '2026-09-27 09:00:00', '2026-09-27 18:00:00',
     'D03 柜 PDU 整体更换，需全柜断电', '陈锋', '陈锋',
     'PENDING', NULL, '2026-09-26 16:00:00', NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO idc_isolation_server (id, isolation_id, server_id, asset_no, brand, model, start_u, size_u, owner_name, status) VALUES
    (1, 1, 14, 'SRV-0014', '华为', 'TaiShan 2280', 1, 2, '国产化试点', 'ONLINE'),
    (2, 1, 15, 'SRV-0015', '华为', 'TaiShan 2280', 4, 2, '国产化试点', 'ONLINE'),
    (3, 1, 16, 'SRV-0016', '戴尔', 'PowerEdge R750xs', 8, 1, '数仓计算', 'ONLINE');

INSERT INTO idc_isolation_order (id, isolation_id, order_id, order_no, order_type, asset_no, target_u, applicant, status_at_freeze) VALUES
    (1, 1, 10, 'WO20260915010', 'MOVE', 'SRV-0016', 20, '陈锋', 'PENDING');

INSERT INTO idc_isolation_event (id, isolation_id, action, operator, detail, created_at) VALUES
    (1, 1, 'CREATE', '马涛', '创建隔离记录，计划 2026-09-16 08:00 ~ 2026-09-16 12:00 断电检修', '2026-09-15 17:30:00'),
    (2, 1, 'ACTIVATE', '马涛', '隔离生效，固定受影响服务器 3 台，冻结施工工单 1 张', '2026-09-16 08:05:00'),
    (3, 1, 'RELEASE', '马涛', '解除隔离：电池组更换完成，双路供电恢复正常，巡检复核无异常；相关施工恢复正常推进', '2026-09-16 11:42:00'),
    (4, 2, 'CREATE', '陈锋', '创建隔离记录，计划 2026-09-27 09:00 ~ 2026-09-27 18:00 断电检修', '2026-09-26 16:00:00');

SET FOREIGN_KEY_CHECKS = 1;
