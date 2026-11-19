package com.charphone.aigateway.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.security.ApiClient;

/**
 * query_risk_orders：交期風險訂單（來不及給客戶）。
 * 風險窗口預設 3 天，含已逾期。僅系統管理者。
 */
@Component
public class QueryRiskOrdersTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryRiskOrdersTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_risk_orders";
    }

    @Override
    public String description() {
        return "查詢交期風險訂單：交期已到或將到（預設 3 天內，含已逾期）、但未出完貨的訂單。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of(),
                Map.of(
                        "riskDays", ToolHandler.prop("INTEGER", "交期在此天數內視為風險（含已逾期），預設 3"),
                        "includeOverdue", ToolHandler.prop("BOOLEAN", "是否包含已逾期交期的訂單（預設 true）"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：只看單一客戶")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        int riskDays = Math.min(Math.max(ToolHandler.intArg(args, "riskDays", 3), 0), 180);
        boolean includeOverdue = !"false".equalsIgnoreCase(
                String.valueOf(args.getOrDefault("includeOverdue", "true")));
        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");

        StringBuilder where = new StringBuilder("""
                WHERE o.close_status = 0
                  AND o.expected_delivery_date IS NOT NULL
                  AND o.expected_delivery_date <= DATE_ADD(CURDATE(), INTERVAL ? DAY)
                """);
        List<Object> params = new ArrayList<>(List.of(riskDays));
        if (!includeOverdue) {
            where.append("AND o.expected_delivery_date >= CURDATE() ");
        }
        if (customerId != null) {
            where.append("AND o.customer_id = ? ");
            params.add(customerId);
        }

        String sql = """
                SELECT o.id, o.order_number, o.quantity, o.price_type, o.expected_delivery_date,
                       DATEDIFF(CURDATE(), o.expected_delivery_date) AS overdue_days,
                       COALESCE((SELECT SUM(quantity) FROM deliveries WHERE order_id = o.id AND status = 1), 0) AS delivered,
                       COALESCE((SELECT SUM(manufactured_qty) FROM productions WHERE order_id = o.id AND status != 3), 0) AS manufactured,
                       (SELECT COUNT(*) FROM productions WHERE order_id = o.id AND status != 3) AS prod_count,
                       c.id AS customer_id, c.customer_number, c.customer_name,
                       p.id AS product_id, p.product_code, p.product_name
                FROM orders o
                JOIN customers c ON c.id = o.customer_id
                JOIN products p ON p.id = o.product_id
                """ + where + """
                  AND COALESCE((SELECT SUM(quantity) FROM deliveries
                                WHERE order_id = o.id AND status = 1), 0) < o.quantity
                ORDER BY o.expected_delivery_date""";

        List<Map<String, Object>> riskOrders = new ArrayList<>();
        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            BigDecimal qty = dec(row.get("quantity"));
            BigDecimal delivered = dec(row.get("delivered"));
            BigDecimal manufactured = dec(row.get("manufactured"));
            int overdueDays = QueryOrdersTool.intVal(row.get("overdue_days"));
            int daysLeft = -overdueDays;

            StatusDeriver.Labeled prodLabel = manufactured.signum() <= 0
                    ? new StatusDeriver.Labeled("NOT_STARTED", "未生產")
                    : (qty.signum() > 0 && manufactured.compareTo(qty) >= 0
                            ? new StatusDeriver.Labeled("COMPLETED", "已完工")
                            : new StatusDeriver.Labeled("IN_PROGRESS", "生產中"));
            StatusDeriver.Labeled delivLabel = StatusDeriver.delivery(delivered, qty);

            BigDecimal rate = qty.signum() > 0
                    ? manufactured.divide(qty, 2, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    : BigDecimal.ZERO;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", row.get("id"));
            m.put("orderNumber", row.get("order_number"));

            Map<String, Object> cust = new LinkedHashMap<>();
            cust.put("id", row.get("customer_id"));
            cust.put("customerNumber", row.get("customer_number"));
            cust.put("customerName", row.get("customer_name"));
            m.put("customer", cust);

            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", row.get("product_id"));
            product.put("productCode", row.get("product_code"));
            product.put("productName", row.get("product_name"));
            m.put("product", product);

            int priceType = QueryOrdersTool.intVal(row.get("price_type"));
            m.put("priceType", priceType);
            m.put("unit", StatusDeriver.unit(priceType));
            m.put("expectedDeliveryDate", String.valueOf(row.get("expected_delivery_date")));
            m.put("daysLeft", daysLeft);
            m.put("orderQty", row.get("quantity"));
            m.put("deliveredQty", row.get("delivered"));
            m.put("undeliveredQty", qty.subtract(delivered).max(BigDecimal.ZERO));
            m.put("productionStatusLabel", prodLabel.label());
            m.put("productionRate", rate);
            m.put("deliveryStatusLabel", delivLabel.label());
            m.put("status", overdueDays > 0 ? "OVERDUE" : "DUE_SOON");
            riskOrders.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskDays", riskDays);
        result.put("count", riskOrders.size());
        result.put("riskOrders", riskOrders);
        return ToolHandler.ok(result);
    }

    private static BigDecimal dec(Object v) {
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }
}
