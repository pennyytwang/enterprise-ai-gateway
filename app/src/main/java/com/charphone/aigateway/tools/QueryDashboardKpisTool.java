package com.charphone.aigateway.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.security.ApiClient;

/**
 * query_dashboard_kpis：經營總覽 KPI（進行中訂單/工單、已出貨未收款、本月營收、低於安全庫存）。
 * 參考 charphone DashboardDataServlet + dashboard.jsp。
 */
@Component
public class QueryDashboardKpisTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryDashboardKpisTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_dashboard_kpis";
    }

    @Override
    public String description() {
        return "查詢企業經營總覽 KPI：進行中訂單數、進行中工單數、已出貨未收款金額與筆數、本月營收、低於安全庫存品項數。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(List.of(), Map.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        Map<String, Object> result = new LinkedHashMap<>();

        // KPI 1: 進行中訂單
        Integer activeOrders = mydbJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE close_status = 0",
                Integer.class);
        result.put("activeOrders", activeOrders != null ? activeOrders : 0);

        // KPI 2: 進行中工單
        Integer activeProductions = mydbJdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM productions p
                        JOIN orders o ON p.order_id = o.id
                        WHERE o.close_status = 0
                          AND p.status != 3
                          AND (p.manufactured_qty < p.produced_qty OR p.manufactured_qty IS NULL)
                        """,
                Integer.class);
        result.put("activeProductions", activeProductions != null ? activeProductions : 0);

        // KPI 3: 已出貨未收款
        Map<String, Object> unpaidResult = new LinkedHashMap<>();
        List<Map<String, Object>> unpaidRows = mydbJdbcTemplate.queryForList(
                """
                        SELECT COUNT(*) AS cnt, COALESCE(SUM(t.outstanding), 0) AS total_outstanding FROM (
                          SELECT o.id,
                            COALESCE(COALESCE(o.actual_amount, o.total_amount), 0)
                              - COALESCE(inv.received, 0) AS outstanding
                          FROM orders o
                          LEFT JOIN (
                            SELECT order_id,
                              SUM(CASE WHEN payment_status = 1 THEN received_amount ELSE 0 END) AS received
                            FROM invoices GROUP BY order_id
                          ) inv ON inv.order_id = o.id
                          WHERE COALESCE(COALESCE(o.actual_amount, o.total_amount), 0) > 0
                            AND COALESCE(inv.received, 0) < COALESCE(COALESCE(o.actual_amount, o.total_amount), 0)
                            AND EXISTS (SELECT 1 FROM deliveries WHERE order_id = o.id)
                        ) t
                        """);
        if (!unpaidRows.isEmpty()) {
            Map<String, Object> row = unpaidRows.get(0);
            unpaidResult.put("count", QueryOrdersTool.intVal(row.get("cnt")));
            unpaidResult.put("totalAmount", row.get("total_outstanding"));
        } else {
            unpaidResult.put("count", 0);
            unpaidResult.put("totalAmount", java.math.BigDecimal.ZERO);
        }
        result.put("unpaidReceivables", unpaidResult);

        // KPI 4: 本月營收
        Map<String, Object> monthlyRevenue = new LinkedHashMap<>();
        List<Map<String, Object>> revRows = mydbJdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(CURDATE(), '%Y-%m') AS month, "
                        + "COALESCE(SUM(total_amount), 0) AS total_amount "
                        + "FROM orders WHERE order_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01')");
        if (!revRows.isEmpty()) {
            Map<String, Object> row = revRows.get(0);
            monthlyRevenue.put("month", row.get("month"));
            monthlyRevenue.put("totalAmount", row.get("total_amount"));
        } else {
            monthlyRevenue.put("month", java.time.LocalDate.now().toString().substring(0, 7));
            monthlyRevenue.put("totalAmount", java.math.BigDecimal.ZERO);
        }
        result.put("monthlyRevenue", monthlyRevenue);

        // KPI 5: 低於安全庫存品項數
        Integer lowStockItems = mydbJdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM (
                          SELECT i.id, i.safety_stock, COALESCE(SUM(s.quantity), 0) AS total_qty
                          FROM inventory_items i
                          LEFT JOIN inventory_stock s ON s.item_id = i.id
                          GROUP BY i.id, i.safety_stock
                          HAVING i.safety_stock > 0 AND total_qty < i.safety_stock
                        ) t
                        """,
                Integer.class);
        result.put("lowStockItems", lowStockItems != null ? lowStockItems : 0);

        return ToolHandler.ok(result);
    }
}
