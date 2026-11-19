package com.charphone.aigateway.tools;

import java.math.BigDecimal;

/**
 * 業務務狀態推導（鐵則：一律推導，不讀 raw status 欄位）。
 * 來源：DEV-GUIDE 第 4 節 + phase0 工具規格。
 */
public final class StatusDeriver {

    private StatusDeriver() {
    }

    public record Labeled(String code, String label) {
    }

    private static final Labeled NOT_STARTED = new Labeled("NOT_STARTED", "未立工單");
    private static final Labeled WAITING = new Labeled("WAITING", "待生產");
    private static final Labeled IN_PROGRESS = new Labeled("IN_PROGRESS", "生產中");
    private static final Labeled COMPLETED = new Labeled("COMPLETED", "已完成");

    /** 訂單層級生產狀態（依 productions 彙總子查詢結果）。 */
    public static Labeled orderProduction(int prodCount, int waiting, int completed) {
        if (prodCount <= 0) {
            return NOT_STARTED;
        }
        if (completed >= prodCount) {
            return COMPLETED;
        }
        if (waiting >= prodCount) {
            return WAITING;
        }
        return IN_PROGRESS;
    }

    /** 工單層級顯示狀態（與 production_list.jsp 一致；status=3 是暫停中，不是取消）。 */
    public static Labeled productionRow(int closeStatus, int rawStatus, java.math.BigDecimal manufactured,
            java.math.BigDecimal produced) {
        if (closeStatus == 1) {
            return new Labeled("CLOSED", "已結案");
        }
        if (rawStatus == 3) {
            return new Labeled("PAUSED", "暫停中");
        }
        BigDecimal m = manufactured == null ? BigDecimal.ZERO : manufactured;
        BigDecimal p = produced == null ? BigDecimal.ZERO : produced;
        if (p.signum() > 0 && m.compareTo(p) >= 0) {
            return COMPLETED;
        }
        if (m.signum() > 0) {
            return IN_PROGRESS;
        }
        return WAITING;
    }

    public static Labeled delivery(java.math.BigDecimal delivered, java.math.BigDecimal quantity) {
        BigDecimal d = delivered == null ? BigDecimal.ZERO : delivered;
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        if (d.compareTo(BigDecimal.ZERO) <= 0) {
            return new Labeled("NOT_DELIVERED", "未出貨");
        }
        if (q.signum() > 0 && d.compareTo(q) >= 0) {
            return new Labeled("DELIVERED", "已出貨");
        }
        return new Labeled("PARTIAL", "部分出貨");
    }

    public static Labeled payment(java.math.BigDecimal received, java.math.BigDecimal amount) {
        BigDecimal r = received == null ? BigDecimal.ZERO : received;
        BigDecimal a = amount == null ? BigDecimal.ZERO : amount;
        if (r.compareTo(BigDecimal.ZERO) <= 0) {
            return new Labeled("UNPAID", "未收");
        }
        if (a.signum() > 0 && r.compareTo(a) >= 0) {
            return new Labeled("PAID", "全收");
        }
        return new Labeled("PARTIAL", "部分收");
    }

    public static String unit(int priceType) {
        return priceType == 1 ? "kg" : "pcs";
    }

    public static String itemTypeLabel(int itemType) {
        return switch (itemType) {
            case 1 -> "原料";
            case 2 -> "半成品";
            default -> "成品";
        };
    }
}
