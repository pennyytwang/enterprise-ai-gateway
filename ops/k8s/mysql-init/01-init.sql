-- =====================================================================
-- AI Gateway 本機 K8s 示範初始化
-- 建立 aigateway / mydb 兩顆資料庫、示範帳號，並載入虛構 ERP 資料。
-- 帳號密碼需與 03-secret.yaml 的 DB_PASSWORD / MYDB_PASSWORD 相同。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS aigateway CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mydb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'aigateway_app'@'%' IDENTIFIED BY 'aigateway_demo_app';
ALTER USER 'aigateway_app'@'%' IDENTIFIED BY 'aigateway_demo_app';
GRANT ALL PRIVILEGES ON aigateway.* TO 'aigateway_app'@'%';

CREATE USER IF NOT EXISTS 'aigateway_ro'@'%' IDENTIFIED BY 'aigateway_demo_ro';
ALTER USER 'aigateway_ro'@'%' IDENTIFIED BY 'aigateway_demo_ro';
GRANT SELECT, INSERT, DELETE ON mydb.* TO 'aigateway_ro'@'%';

FLUSH PRIVILEGES;
USE mydb;
SET NAMES utf8mb4;

-- =====================================================================
-- CharPhone 訂單管理系統 - 資料庫結構（schema only）
-- =====================================================================

-- =====================================================================
-- 1. 使用者管理表
-- =====================================================================
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) DEFAULT NULL,
    display_name VARCHAR(100) DEFAULT NULL,
    role VARCHAR(20) DEFAULT 'user',
    email VARCHAR(100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 2. 客戶管理表
-- =====================================================================
CREATE TABLE IF NOT EXISTS customers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_number VARCHAR(20) DEFAULT NULL,
    customer_name VARCHAR(100) NOT NULL,
    phone VARCHAR(30),
    tax_id VARCHAR(20),
    contact_person VARCHAR(50),
    address VARCHAR(200),
    delivery_address VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_customer_number UNIQUE (customer_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_customer_number ON customers (customer_number);

-- =====================================================================
-- 3. 產品管理表
-- =====================================================================
CREATE TABLE IF NOT EXISTS products (
    id INT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(50) UNIQUE,
    product_name VARCHAR(100) NOT NULL,
    price_type INT DEFAULT 1,
    unit_weight DECIMAL(10, 3),
    unit_price DECIMAL(10, 2),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 4. 訂單表
-- =====================================================================
CREATE TABLE IF NOT EXISTS orders (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    product_id INT NOT NULL,
    price_type INT DEFAULT 1,
    unit_price DECIMAL(10, 2),
    unit_weight DECIMAL(10, 3),
    quantity DECIMAL(10, 3) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    order_date DATE NOT NULL,
    delivery_date DATE,
    expected_delivery_date DATE,
    actual_amount DECIMAL(12, 2),
    amount_diff_reason TEXT,
    status INT DEFAULT 0,
    close_status TINYINT DEFAULT 0 COMMENT '0=未結案 1=已結案',
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 5. 應收帳款表
-- =====================================================================
CREATE TABLE IF NOT EXISTS invoices (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_id INT NOT NULL,
    invoice_number VARCHAR(50) NOT NULL UNIQUE,
    amount DECIMAL(12, 2) NOT NULL,
    issue_date DATE NOT NULL,
    due_date DATE,
    status INT DEFAULT 0,
    payment_status TINYINT DEFAULT 0 COMMENT '0=未收款 1=已收款',
    payment_date DATE DEFAULT NULL COMMENT '收款日期',
    received_amount DECIMAL(12,2) DEFAULT NULL COMMENT '實收金額',
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 6. 生產排程表
-- =====================================================================
CREATE TABLE IF NOT EXISTS productions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    production_number VARCHAR(50) NOT NULL UNIQUE,
    product_id INT NOT NULL,
    price_type INT DEFAULT 1,
    unit_price DECIMAL(10, 2),
    unit_weight DECIMAL(10, 3),
    quantity DECIMAL(10, 3) NOT NULL,
    produced_qty DECIMAL(10, 3) DEFAULT 0,
    manufactured_qty DECIMAL(10, 3) DEFAULT 0,
    status INT DEFAULT 0,
    close_status INT DEFAULT 0,
    start_date DATE,
    target_date DATE,
    completed_date DATE,
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 7. 出貨/送貨表
-- =====================================================================
CREATE TABLE IF NOT EXISTS deliveries (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    delivery_number VARCHAR(50) NOT NULL UNIQUE,
    delivery_date DATE NOT NULL,
    quantity DECIMAL(10, 3) NOT NULL,
    status INT DEFAULT 1,
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 8. 倉庫表
-- =====================================================================
CREATE TABLE IF NOT EXISTS warehouses (
    id INT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(20) NOT NULL UNIQUE,
    warehouse_name VARCHAR(100) NOT NULL,
    contact_person VARCHAR(50),
    phone VARCHAR(30),
    address VARCHAR(200),
    status INT DEFAULT 1,
    note TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 9. 庫存項目表
-- =====================================================================
CREATE TABLE IF NOT EXISTS inventory_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    item_code VARCHAR(50) NOT NULL UNIQUE,
    item_name VARCHAR(100) NOT NULL,
    item_type INT NOT NULL DEFAULT 1,
    product_id INT DEFAULT NULL,
    unit VARCHAR(10) NOT NULL DEFAULT 'pcs',
    unit_cost DECIMAL(12, 2) DEFAULT 0,
    safety_stock DECIMAL(10, 3) DEFAULT 0,
    status INT DEFAULT 1,
    note TEXT,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 10. 庫存統計表
-- =====================================================================
CREATE TABLE IF NOT EXISTS inventory_stock (
    id INT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id INT NOT NULL,
    item_id INT NOT NULL,
    quantity DECIMAL(10, 3) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
    FOREIGN KEY (item_id) REFERENCES inventory_items(id) ON DELETE RESTRICT,
    UNIQUE KEY uk_wh_item (warehouse_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 11. 庫存交易日誌表
-- =====================================================================
CREATE TABLE IF NOT EXISTS inventory_transactions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    transaction_number VARCHAR(50) NOT NULL UNIQUE,
    warehouse_id INT NOT NULL,
    item_id INT NOT NULL,
    transaction_type INT NOT NULL,
    quantity DECIMAL(10, 3) NOT NULL,
    balance_before DECIMAL(10, 3) NOT NULL,
    balance_after DECIMAL(10, 3) NOT NULL,
    ref_type VARCHAR(30) DEFAULT NULL,
    ref_id INT DEFAULT NULL,
    order_id INT DEFAULT NULL,
    production_id INT DEFAULT NULL,
    note TEXT,
    created_by INT DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
    FOREIGN KEY (item_id) REFERENCES inventory_items(id) ON DELETE RESTRICT,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO users (id, username, password, display_name, role) VALUES (1, 'admin', '!', '系統管理員', 'admin');

-- =====================================================================
-- CharPhone 訂單管理系統 - 虛構示範資料
-- =====================================================================
-- 本檔所有公司、人名、統編、電話、地址、單號與金額皆為虛構，
-- 僅供公開展示與本機開發使用，請勿放入任何真實營運資料。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 客戶
-- ---------------------------------------------------------------------
INSERT IGNORE INTO customers
    (id, customer_number, customer_name, phone, tax_id, contact_person, address, delivery_address)
VALUES
    (1, 'CUS-001', '範例科技股份有限公司', '02-0000-0001', '00000001', '王小明', '台北市信義區範例路 1 號', '台北市信義區範例路 1 號'),
    (2, 'CUS-002', '測試工業有限公司', '04-0000-0002', '00000002', '陳小華', '彰化縣彰化市範例街 2 號', '彰化縣彰化市範例街 2 號'),
    (3, 'CUS-003', '展示五金股份有限公司', '04-0000-0003', '00000003', '林小美', '台中市西屯區範例路 3 號', '台中市西屯區範例路 3 號'),
    (4, 'CUS-004', '模擬衛浴股份有限公司', '02-0000-0004', '00000004', '張小強', '新北市三重區範例路 4 號', '新北市三重區範例路 4 號'),
    (5, 'CUS-005', '樣本銅器有限公司', '04-0000-0005', '00000005', '黃小英', '彰化縣鹿港鎮範例巷 5 號', '彰化縣鹿港鎮範例巷 5 號'),
    (6, 'CUS-006', '範例精密股份有限公司', '07-0000-0006', '00000006', '吳小偉', '高雄市楠梓區範例路 6 號', '高雄市楠梓區範例路 6 號'),
    (7, 'CUS-007', '測試金屬有限公司', '06-0000-0007', '00000007', '李小花', '台南市永康區範例街 7 號', '台南市永康區範例街 7 號'),
    (8, 'CUS-008', '展示製造股份有限公司', '03-0000-0008', '00000008', '周小君', '桃園市中壢區範例路 8 號', '桃園市中壢區範例路 8 號');

-- ---------------------------------------------------------------------
-- 產品
-- ---------------------------------------------------------------------
INSERT IGNORE INTO products
    (id, product_code, product_name, price_type, unit_weight, unit_price, description)
VALUES
    (1,  'FC-001', '廚房龍頭閥心 A（示範）', 2, 1.500, 100.00, '虛構產品，僅供展示'),
    (2,  'FC-002', '廚房龍頭閥心 B（示範）', 2, 1.800, 120.00, '虛構產品，僅供展示'),
    (3,  'EL-001', '彎頭 2 吋（示範）', 1, 0.900, 35.00, '虛構產品，僅供展示'),
    (4,  'BV-001', '球閥本體（示範）', 2, 1.600, 120.00, '虛構產品，僅供展示'),
    (5,  'FL-001', '法蘭盤 4 吋（示範）', 1, 0.600, 45.00, '虛構產品，僅供展示'),
    (6,  'FL-002', '法蘭盤 6 吋（示範）', 1, 0.850, 65.00, '虛構產品，僅供展示'),
    (7,  'FT-001', '三通接頭（示範）', 1, 1.200, 50.00, '虛構產品，僅供展示'),
    (8,  'CP-001', '管帽（示範）', 1, 0.800, 20.00, '虛構產品，僅供展示'),
    (9,  'AD-001', '異徑接頭（示範）', 1, 1.100, 40.00, '虛構產品，僅供展示'),
    (10, 'GS-001', '墊片（示範）', 1, 0.100, 5.00, '虛構產品，僅供展示');

-- ---------------------------------------------------------------------
-- 倉庫
-- ---------------------------------------------------------------------
INSERT IGNORE INTO warehouses
    (id, warehouse_code, warehouse_name, contact_person, phone, address, status, note)
VALUES
    (1, 'WH-RAW', '原料倉（示範）', '王小明', '02-0000-0001', '台北市信義區範例路 1 號', 1, '虛構倉庫'),
    (2, 'WH-FG',  '成品倉（示範）', '陳小華', '04-0000-0002', '彰化縣彰化市範例街 2 號', 1, '虛構倉庫'),
    (3, 'WH-WIP', '半成品倉（示範）', '林小美', '04-0000-0003', '台中市西屯區範例路 3 號', 1, '虛構倉庫');

-- ---------------------------------------------------------------------
-- 庫存項目（1=原料 2=半成品 3=成品）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO inventory_items
    (id, item_code, item_name, item_type, product_id, unit, unit_cost, safety_stock, status, note)
VALUES
    (1, 'RM-CU',   '銅錠（示範）',       1, NULL, 'kg',  180.00, 100.000, 1, '虛構原料'),
    (2, 'RM-ZN',   '鋅錠（示範）',       1, NULL, 'kg',   80.00,  80.000, 1, '虛構原料'),
    (3, 'RM-SS',   '不鏽鋼料（示範）',   1, NULL, 'kg',  120.00,  50.000, 1, '虛構原料'),
    (4, 'WIP-FC',  '龍頭半成品（示範）', 2, 1,    'pcs',  60.00,  20.000, 1, '虛構半成品'),
    (5, 'FG-FC001','廚房龍頭成品 A（示範）', 3, 1, 'pcs',  95.00,  30.000, 1, '虛構成品'),
    (6, 'FG-EL001','彎頭成品（示範）',   3, 3,    'pcs',  30.00,  50.000, 1, '虛構成品');

-- ---------------------------------------------------------------------
-- 庫存現況
-- ---------------------------------------------------------------------
INSERT IGNORE INTO inventory_stock (warehouse_id, item_id, quantity)
VALUES
    (1, 1, 405.000),
    (1, 2, 300.000),
    (1, 3, 200.000),
    (3, 4, 120.000),
    (2, 5,   0.000),
    (2, 6, 350.000);

-- ---------------------------------------------------------------------
-- 訂單
-- ---------------------------------------------------------------------
INSERT IGNORE INTO orders
    (id, customer_id, order_number, product_id, price_type, unit_price, unit_weight,
     quantity, total_amount, order_date, delivery_date, expected_delivery_date,
     actual_amount, amount_diff_reason, status, close_status, note)
VALUES
    (1,  1, 'ORD-2026-001', 1,  2, 100.00, 1.500, 100.000, 10000.00, '2026-08-01', NULL, '2026-08-15', 10000.00, NULL, 0, 0, '示範訂單'),
    (2,  2, 'ORD-2026-002', 3,  1,  35.00, 0.900, 500.000, 17500.00, '2026-08-03', '2026-08-18', '2026-08-20', 17500.00, NULL, 0, 1, '示範訂單'),
    (3,  3, 'ORD-2026-003', 5,  1,  45.00, 0.600, 200.000,  9000.00, '2026-08-05', NULL, '2026-08-25',  9000.00, NULL, 0, 0, '示範訂單'),
    (4,  4, 'ORD-2026-004', 2,  2, 120.00, 1.800,  80.000,  9600.00, '2026-08-10', NULL, '2026-08-30',  9600.00, NULL, 0, 0, '示範訂單'),
    (5,  5, 'ORD-2026-005', 4,  2, 120.00, 1.600,  60.000,  7200.00, '2026-08-12', '2026-08-27', '2026-08-28',  7200.00, NULL, 0, 1, '示範訂單'),
    (6,  6, 'ORD-2026-006', 6,  1,  65.00, 0.850, 150.000,  9750.00, '2026-08-15', '2026-09-01', '2026-09-05',  9750.00, NULL, 0, 1, '示範訂單'),
    (7,  7, 'ORD-2026-007', 7,  1,  50.00, 1.200, 300.000, 15000.00, '2026-08-18', '2026-09-02', '2026-09-10', 15000.00, NULL, 0, 0, '示範訂單（部分出貨）'),
    (8,  8, 'ORD-2026-008', 8,  1,  20.00, 0.800, 400.000,  8000.00, '2026-08-20', NULL, '2026-09-15',  8000.00, NULL, 0, 0, '示範訂單'),
    (9,  1, 'ORD-2026-009', 9,  1,  40.00, 1.100, 250.000, 10000.00, '2026-09-01', '2026-09-10', '2026-09-20', 10000.00, NULL, 0, 0, '示範訂單（部分出貨）'),
    (10, 2, 'ORD-2026-010', 10, 1,   5.00, 0.100, 1000.000, 5000.00, '2026-09-03', '2026-09-12', '2026-09-22',  5000.00, NULL, 0, 1, '示範訂單'),
    (11, 3, 'ORD-2026-011', 1,  2, 100.00, 1.500, 150.000, 15000.00, '2026-09-05', NULL, '2026-09-30', 15000.00, NULL, 0, 0, '示範訂單'),
    (12, 4, 'ORD-2026-012', 3,  1,  35.00, 0.900, 800.000, 28000.00, '2026-09-08', '2026-09-15', '2026-10-05', 28000.00, NULL, 0, 0, '示範訂單（部分出貨）');

-- ---------------------------------------------------------------------
-- 生產工單（0=待生產 1=生產中 2=已完成 3=暫停中 4=已結案）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO productions
    (id, order_id, production_number, product_id, price_type, unit_price, unit_weight,
     quantity, produced_qty, manufactured_qty, status, close_status,
     start_date, target_date, completed_date, note)
VALUES
    (1,  1,  'PROD-2026-001', 1,  2, 100.00, 1.500, 100.000,    0.000,    0.000, 0, 0, '2026-08-02', '2026-08-12', NULL, '示範工單'),
    (2,  2,  'PROD-2026-002', 3,  1,  35.00, 0.900, 500.000,  500.000,  500.000, 2, 0, '2026-08-04', '2026-08-18', '2026-08-17', '示範工單'),
    (3,  3,  'PROD-2026-003', 5,  1,  45.00, 0.600, 200.000,  120.000,  120.000, 1, 0, '2026-08-06', '2026-08-25', NULL, '示範工單'),
    (4,  4,  'PROD-2026-004', 2,  2, 120.00, 1.800,  80.000,    0.000,    0.000, 3, 0, '2026-08-11', '2026-08-30', NULL, '示範工單（暫停）'),
    (5,  5,  'PROD-2026-005', 4,  2, 120.00, 1.600,  60.000,   60.000,   60.000, 4, 1, '2026-08-13', '2026-08-28', '2026-08-27', '示範工單'),
    (6,  6,  'PROD-2026-006', 6,  1,  65.00, 0.850, 150.000,  150.000,  150.000, 2, 1, '2026-08-16', '2026-09-01', '2026-08-31', '示範工單'),
    (7,  7,  'PROD-2026-007', 7,  1,  50.00, 1.200, 300.000,  300.000,  300.000, 2, 1, '2026-08-19', '2026-09-02', '2026-09-01', '示範工單'),
    (8,  8,  'PROD-2026-008', 8,  1,  20.00, 0.800, 400.000,    0.000,    0.000, 0, 0, '2026-08-21', '2026-09-15', NULL, '示範工單'),
    (9,  9,  'PROD-2026-009', 9,  1,  40.00, 1.100, 250.000,  100.000,  100.000, 1, 0, '2026-09-02', '2026-09-20', NULL, '示範工單'),
    (10, 10, 'PROD-2026-010', 10, 1,   5.00, 0.100, 1000.000, 1000.000, 1000.000, 2, 1, '2026-09-04', '2026-09-12', '2026-09-11', '示範工單'),
    (11, 11, 'PROD-2026-011', 1,  2, 100.00, 1.500, 150.000,    0.000,    0.000, 0, 0, '2026-09-06', '2026-09-30', NULL, '示範工單'),
    (12, 12, 'PROD-2026-012', 3,  1,  35.00, 0.900, 800.000,  400.000,  400.000, 1, 0, '2026-09-09', '2026-10-05', NULL, '示範工單');

-- ---------------------------------------------------------------------
-- 出貨
-- ---------------------------------------------------------------------
INSERT IGNORE INTO deliveries
    (id, order_id, delivery_number, delivery_date, quantity, status, note)
VALUES
    (1, 2,  'DEL-2026-001', '2026-08-18', 500.000, 1, '示範出貨'),
    (2, 5,  'DEL-2026-002', '2026-08-27',  60.000, 1, '示範出貨'),
    (3, 6,  'DEL-2026-003', '2026-09-01', 150.000, 1, '示範出貨'),
    (4, 7,  'DEL-2026-004', '2026-09-02', 150.000, 1, '示範部分出貨'),
    (5, 9,  'DEL-2026-005', '2026-09-10', 100.000, 1, '示範部分出貨'),
    (6, 10, 'DEL-2026-006', '2026-09-12', 1000.000, 1, '示範出貨'),
    (7, 12, 'DEL-2026-007', '2026-09-15', 400.000, 1, '示範部分出貨');

-- ---------------------------------------------------------------------
-- 應收帳款
-- ---------------------------------------------------------------------
INSERT IGNORE INTO invoices
    (id, customer_id, order_id, invoice_number, amount, issue_date, due_date,
     status, payment_status, payment_date, received_amount, note)
VALUES
    (1, 1, 1,  'INV-2026-001', 10000.00, '2026-08-10', '2026-09-09', 0, 0, NULL, NULL, '示範帳款'),
    (2, 2, 2,  'INV-2026-002', 17500.00, '2026-08-19', '2026-09-18', 0, 1, '2026-09-10', 17500.00, '示範帳款'),
    (3, 5, 5,  'INV-2026-003',  7200.00, '2026-08-28', '2026-09-27', 0, 1, '2026-09-20',  7200.00, '示範帳款'),
    (4, 6, 6,  'INV-2026-004',  9750.00, '2026-09-02', '2026-10-02', 0, 0, NULL, NULL, '示範帳款'),
    (5, 7, 7,  'INV-2026-005', 15000.00, '2026-09-03', '2026-10-03', 0, 0, NULL, NULL, '示範帳款'),
    (6, 1, 9,  'INV-2026-006', 10000.00, '2026-09-11', '2026-10-11', 0, 1, '2026-09-30', 10000.00, '示範帳款'),
    (7, 2, 10, 'INV-2026-007',  5000.00, '2026-09-13', '2026-10-13', 0, 1, '2026-10-01',  5000.00, '示範帳款'),
    (8, 4, 12, 'INV-2026-008', 28000.00, '2026-09-16', '2026-10-16', 0, 0, NULL, NULL, '示範帳款');

-- ---------------------------------------------------------------------
-- 庫存異動（1=入庫 2=出庫 3=調撥入 4=調撥出 5=盤盈 6=盤虧）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO inventory_transactions
    (id, transaction_number, warehouse_id, item_id, transaction_type, quantity,
     balance_before, balance_after, ref_type, ref_id, order_id, production_id, note, created_by)
VALUES
    (1,  'TXN-2026-001', 1, 1, 1, 500.000,   0.000, 500.000, 'manual',     NULL, NULL, NULL, '示範入庫', 1),
    (2,  'TXN-2026-002', 1, 2, 1, 300.000,   0.000, 300.000, 'manual',     NULL, NULL, NULL, '示範入庫', 1),
    (3,  'TXN-2026-003', 1, 3, 1, 200.000,   0.000, 200.000, 'manual',     NULL, NULL, NULL, '示範入庫', 1),
    (4,  'TXN-2026-004', 3, 4, 1, 120.000,   0.000, 120.000, 'production', 3,    NULL, 3,    '示範生產入庫', 1),
    (5,  'TXN-2026-005', 2, 6, 1, 500.000,   0.000, 500.000, 'production', 2,    NULL, 2,    '示範生產入庫', 1),
    (6,  'TXN-2026-006', 2, 5, 1,  60.000,   0.000,  60.000, 'production', 5,    NULL, 5,    '示範生產入庫', 1),
    (7,  'TXN-2026-007', 2, 5, 2,  60.000,  60.000,   0.000, 'delivery',   2,    5,    NULL, '示範出貨出庫', 1),
    (8,  'TXN-2026-008', 2, 6, 2, 150.000, 500.000, 350.000, 'delivery',   3,    6,    NULL, '示範出貨出庫', 1),
    (9,  'TXN-2026-009', 1, 1, 2, 100.000, 500.000, 400.000, 'production', 3,    NULL, 3,    '示範生產領料', 1),
    (10, 'TXN-2026-010', 1, 1, 5,   5.000, 400.000, 405.000, 'manual',     NULL, NULL, NULL, '示範盤盈', 1);

CREATE TABLE IF NOT EXISTS rag_document (
  id BIGINT NOT NULL AUTO_INCREMENT,
  doc_id VARCHAR(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  filename VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  chunk_index INT NOT NULL,
  chunk_text MEDIUMTEXT COLLATE utf8mb4_unicode_ci NOT NULL,
  embedding JSON NOT NULL,
  total_chunks INT NOT NULL,
  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_doc_id (doc_id),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;