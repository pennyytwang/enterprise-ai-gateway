package com.charphone.aigateway.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.security.ApiClient;

/**
 * query_production：工單/生產進度查詢。
 *
 * 鐵則：status=3 是「暫停中」不是取消；狀態一律推導（StatusDeriver.productionRow）。
 */
@Component
public class QueryProductionTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryProductionTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_production";
    }

    @Override
    public String description() {
        return "查詢工單/生產進度。可依目標日區間、訂單號、品項關鍵字、顯示狀態篩選；回傳完工率與入庫狀態。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of(),
                Map.of(
                        "dateFrom", ToolHandler.prop("STRING", "選擇性：目標日(target_date)起始，YYYY-MM-DD"),
                        "dateTo", ToolHandler.prop("STRING", "選擇性：目標日結束，YYYY-MM-DD"),
                        "orderNumber", ToolHandler.prop("STRING", "選擇性：訂單號碼（可部分比對）"),
                        "keyword", ToolHandler.prop("STRING", "選擇性：品項名稱關鍵字"),
                        "status", ToolHandler.enumProp(
                                List.of("WAITING", "IN_PROGRESS", "COMPLETED", "PAUSED", "CLOSED"),
                                "選擇性：顯示狀態（待生產/生產中/已完成/暫停中/已結案）"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：客戶 id")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        String dateFrom = ToolHandler.strArgOrNull(args, "dateFrom");
        String dateTo = ToolHandler.strArgOrNull(args, "dateTo");
        String orderNumber = ToolHandler.strArgOrNull(args, "orderNumber");
        String keyword = ToolHandler.strArgOrNull(args, "keyword");
        String statusFilter = ToolHandler.strArgOrNull(args, "status");
        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");

        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append("AND p.target_date >= ? ");
            params.add(LocalDate.parse(dateFrom.trim()));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append("AND p.target_date <= ? ");
            params.add(LocalDate.parse(dateTo.trim()));
        }
        if (orderNumber != null && !orderNumber.isBlank()) {
            where.append("AND o.order_number LIKE ? ");
            params.add("%" + orderNumber.trim() + "%");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND pr.product_name LIKE ? ");
            params.add("%" + keyword.trim() + "%");
        }
        if (customerId != null) {
            where.append("AND o.customer_id = ? ");
            params.add(customerId);
        }

        int pageSize = Math.min(ToolHandler.intArg(args, "pageSize", 200), 200);

        String sql = """
                SELECT p.id, p.production_number, p.produced_qty, p.manufactured_qty,
                       p.status, p.close_status, p.start_date, p.target_date, p.completed_date,
                       p.price_type, o.id AS order_id, o.order_number, o.quantity AS order_quantity,
                       c.id AS customer_id, c.customer_number, c.customer_name,
                       pr.id AS product_id, pr.product_code, pr.product_name,
                       COALESCE((SELECT SUM(quantity) FROM inventory_transactions
                                 WHERE ref_type='production' AND ref_id=p.id AND transaction_type=1), 0)
                         - COALESCE((SELECT SUM(quantity) FROM inventory_transactions
                                     WHERE ref_type='production' AND ref_id=p.id AND transaction_type=2), 0)
                         AS net_received
                FROM productions p
                JOIN orders o ON o.id = p.order_id
                JOIN customers c ON c.id = o.customer_id
                JOIN products pr ON pr.id = p.product_id
                """ + where + " ORDER BY p.target_date, p.id LIMIT " + pageSize;

        List<Map<String, Object>> productions = new ArrayList<>();
        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            BigDecimal produced = dec(row.get("produced_qty"));
            BigDecimal manufactured = dec(row.get("manufactured_qty"));
            BigDecimal netReceived = dec(row.get("net_received"));

            StatusDeriver.Labeled labeled = StatusDeriver.productionRow(
                    QueryOrdersTool.intVal(row.get("close_status")),
                    QueryOrdersTool.intVal(row.get("status")),
                    manufactured, produced);
            if (statusFilter != null && !statusFilter.isBlank()
                    && !statusFilter.equals(labeled.code())) {
                continue;
            }

            BigDecimal rate = produced.signum() > 0
                    ? manufactured.divide(produced, 2, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    : BigDecimal.ZERO;
            String receiptStatus = netReceived.signum() <= 0 ? "未入庫"
                    : (produced.signum() > 0 && netReceived.compareTo(produced) >= 0) ? "已入庫" : "部分入庫";

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productionId", row.get("id"));
            m.put("productionNumber", row.get("production_number"));

            Map<String, Object> order = new LinkedHashMap<>();
            order.put("orderId", row.get("order_id"));
            order.put("orderNumber", row.get("order_number"));
            m.put("order", order);

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
            m.put("plannedQty", row.get("produced_qty"));
            m.put("manufacturedQty", row.get("manufactured_qty"));
            m.put("remainingQty", produced.subtract(manufactured).max(BigDecimal.ZERO));
            m.put("completionRate", rate);
            m.put("status", labeled.code());
            m.put("statusLabel", labeled.label());
            m.put("receiptStatus", receiptStatus);
            m.put("startDate", row.get("start_date") == null ? null : String.valueOf(row.get("start_date")));
            m.put("targetDate", row.get("target_date") == null ? null : String.valueOf(row.get("target_date")));
            m.put("completedDate", row.get("completed_date") == null ? null : String.valueOf(row.get("completed_date")));
            m.put("closeStatus", QueryOrdersTool.intVal(row.get("close_status")));
            productions.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", productions.size());
        result.put("productions", productions);
        return ToolHandler.ok(result);
    }

    private static BigDecimal dec(Object v) {
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }
}
