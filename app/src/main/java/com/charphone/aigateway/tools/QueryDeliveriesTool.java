package com.charphone.aigateway.tools;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.security.ApiClient;

/**
 * query_deliveries：出貨紀錄查詢（依日期區間、客戶、訂單過濾，附訂單/客戶資訊）。
 * 預設只回有效出貨（status=1），includeCancelled=true 可含已取消紀錄。
 */
@Component
public class QueryDeliveriesTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryDeliveriesTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_deliveries";
    }

    @Override
    public String description() {
        return "查詢出貨紀錄。可依日期區間、客戶、訂單號碼過濾。預設只回有效出貨（已出貨），includeCancelled=true 可包含已取消紀錄。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of("dateFrom", "dateTo"),
                Map.of(
                        "dateFrom", ToolHandler.prop("STRING", "查詢區間起始日（依 delivery_date），格式 YYYY-MM-DD，含當日"),
                        "dateTo", ToolHandler.prop("STRING", "查詢區間結束日（依 delivery_date），格式 YYYY-MM-DD，含當日"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：客戶 id"),
                        "orderNumber", ToolHandler.prop("STRING", "選擇性：訂單號碼精確查詢"),
                        "includeCancelled", ToolHandler.prop("BOOLEAN", "true=包含已取消的出貨紀錄（預設 false，只回 status=1）"),
                        "page", ToolHandler.prop("INTEGER", "頁碼（預設 1）"),
                        "pageSize", ToolHandler.prop("INTEGER", "每頁筆數（預設 20，最大 100）")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        LocalDate dateFrom = LocalDate.parse(ToolHandler.strArg(args, "dateFrom"));
        LocalDate dateTo = LocalDate.parse(ToolHandler.strArg(args, "dateTo"));
        if (dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE: dateFrom 不可晚於 dateTo");
        }
        if (ChronoUnit.DAYS.between(dateFrom, dateTo) > 365) {
            throw new IllegalArgumentException("DATE_RANGE_TOO_LARGE: 查詢區間上限 1 年，請縮小日期範圍");
        }
        int page = Math.max(ToolHandler.intArg(args, "page", 1), 1);
        int pageSize = Math.min(ToolHandler.intArg(args, "pageSize", 20), 100);
        boolean includeCancelled = "true".equalsIgnoreCase(String.valueOf(args.get("includeCancelled")));

        StringBuilder where = new StringBuilder("WHERE d.delivery_date >= ? AND d.delivery_date <= ? ");
        List<Object> params = new ArrayList<>(List.of(dateFrom, dateTo));

        if (!includeCancelled) {
            where.append("AND d.status = 1 ");
        }
        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");
        if (customerId != null) {
            where.append("AND o.customer_id = ? ");
            params.add(customerId);
        }
        String orderNumber = ToolHandler.strArgOrNull(args, "orderNumber");
        if (orderNumber != null && !orderNumber.isBlank()) {
            where.append("AND o.order_number = ? ");
            params.add(orderNumber.trim());
        }

        Long total = mydbJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries d JOIN orders o ON o.id = d.order_id " + where,
                Long.class, params.toArray());

        String sql = """
                SELECT d.id, d.delivery_number, d.delivery_date, d.quantity, d.status, d.note,
                       o.id AS order_id, o.order_number, o.total_amount AS order_total_amount,
                       o.price_type,
                       p.product_code, p.product_name,
                       c.id AS customer_id, c.customer_number, c.customer_name
                FROM deliveries d
                JOIN orders o ON o.id = d.order_id
                JOIN products p ON p.id = o.product_id
                JOIN customers c ON c.id = o.customer_id
                """ + where + " ORDER BY d.delivery_date DESC, d.delivery_number LIMIT "
                + pageSize + " OFFSET " + ((page - 1) * pageSize);

        List<Map<String, Object>> deliveries = new ArrayList<>();
        java.math.BigDecimal totalQty = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        int cancelledCount = 0;

        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            int status = QueryOrdersTool.intVal(row.get("status"));
            java.math.BigDecimal qty = row.get("quantity") instanceof Number n
                    ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;

            if (status == 1) {
                totalQty = totalQty.add(qty);
                java.math.BigDecimal unitPrice = row.get("order_total_amount") instanceof Number
                        ? java.math.BigDecimal.valueOf(
                                ((Number) row.get("order_total_amount")).doubleValue())
                        : java.math.BigDecimal.ZERO;
                totalAmount = totalAmount.add(unitPrice);
            } else {
                cancelledCount++;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("deliveryNumber", row.get("delivery_number"));
            m.put("deliveryDate", String.valueOf(row.get("delivery_date")));
            m.put("quantity", row.get("quantity"));
            m.put("status", status);
            m.put("statusLabel", status == 1 ? "已出貨" : "取消");
            m.put("note", row.get("note"));

            Map<String, Object> order = new LinkedHashMap<>();
            order.put("orderId", row.get("order_id"));
            order.put("orderNumber", row.get("order_number"));
            order.put("productName", row.get("product_name"));
            order.put("productCode", row.get("product_code"));
            m.put("order", order);

            Map<String, Object> customer = new LinkedHashMap<>();
            customer.put("id", row.get("customer_id"));
            customer.put("customerNumber", row.get("customer_number"));
            customer.put("customerName", row.get("customer_name"));
            m.put("customer", customer);

            deliveries.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDeliveredQty", totalQty);
        summary.put("totalAmount", totalAmount);
        summary.put("cancelledCount", cancelledCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("summary", summary);
        result.put("deliveries", deliveries);
        return ToolHandler.ok(result);
    }
}
