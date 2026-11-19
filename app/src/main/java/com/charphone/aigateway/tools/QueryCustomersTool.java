package com.charphone.aigateway.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.security.ApiClient;

/**
 * query_customers：客戶主檔查詢（關鍵字搜尋、ID/編號精確查詢，附訂單統計）。
 * 無需管理者權限（客戶主檔非敏感資料）。
 */
@Component
public class QueryCustomersTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryCustomersTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_customers";
    }

    @Override
    public String description() {
        return "查詢客戶主檔。可依關鍵字（名稱/編號/電話）搜尋或依 ID 精確查詢，附帶訂單統計。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of(),
                Map.of(
                        "keyword", ToolHandler.prop("STRING", "選擇性：客戶名稱、編號或電話關鍵字（模糊搜尋）"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：客戶 id 精確查詢"),
                        "customerNumber", ToolHandler.prop("STRING", "選擇性：客戶編號精確查詢（如 04-000001）"),
                        "page", ToolHandler.prop("INTEGER", "頁碼（預設 1）"),
                        "pageSize", ToolHandler.prop("INTEGER", "每頁筆數（預設 20，最大 100）")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        int page = Math.max(ToolHandler.intArg(args, "page", 1), 1);
        int pageSize = Math.min(ToolHandler.intArg(args, "pageSize", 20), 100);

        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");
        if (customerId != null) {
            where.append("AND c.id = ? ");
            params.add(customerId);
        }
        String customerNumber = ToolHandler.strArgOrNull(args, "customerNumber");
        if (customerNumber != null && !customerNumber.isBlank()) {
            where.append("AND c.customer_number = ? ");
            params.add(customerNumber.trim());
        }
        String keyword = ToolHandler.strArgOrNull(args, "keyword");
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND (c.customer_name LIKE ? OR c.customer_number LIKE ? OR c.phone LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        Long total = mydbJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customers c " + where,
                Long.class, params.toArray());

        String sql = """
                SELECT c.id, c.customer_number, c.customer_name, c.phone, c.tax_id,
                       c.contact_person, c.address, c.delivery_address,
                       c.created_at, c.updated_at
                FROM customers c
                """ + where + " ORDER BY c.customer_number LIMIT " + pageSize + " OFFSET " + ((page - 1) * pageSize);

        List<Map<String, Object>> customers = new ArrayList<>();
        List<Object> ids = new ArrayList<>();

        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("customerNumber", row.get("customer_number"));
            m.put("customerName", row.get("customer_name"));
            m.put("phone", row.get("phone"));
            m.put("taxId", row.get("tax_id"));
            m.put("contactPerson", row.get("contact_person"));
            m.put("address", row.get("address"));
            m.put("deliveryAddress", row.get("delivery_address"));
            m.put("createdAt", row.get("created_at") == null ? null : String.valueOf(row.get("created_at")));
            m.put("updatedAt", row.get("updated_at") == null ? null : String.valueOf(row.get("updated_at")));
            m.put("orderStats", new LinkedHashMap<>());
            customers.add(m);
            ids.add(row.get("id"));
        }

        if (!ids.isEmpty()) {
            String inClause = ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("'0'");
            String statsSql = """
                    SELECT customer_id, COUNT(*) AS total_orders,
                           COALESCE(SUM(total_amount), 0) AS total_amount,
                           MAX(order_date) AS last_order_date
                    FROM orders
                    WHERE customer_id IN (""" + inClause + """
                    )
                    GROUP BY customer_id""";
            for (Map<String, Object> srow : mydbJdbcTemplate.queryForList(statsSql, ids.toArray())) {
                for (Map<String, Object> c : customers) {
                    if (QueryOrdersTool.intVal(srow.get("customer_id")) == QueryOrdersTool.intVal(c.get("id"))) {
                        Map<String, Object> stats = new LinkedHashMap<>();
                        stats.put("totalOrders", QueryOrdersTool.intVal(srow.get("total_orders")));
                        stats.put("totalAmount", srow.get("total_amount"));
                        stats.put("lastOrderDate", srow.get("last_order_date") == null
                                ? null : String.valueOf(srow.get("last_order_date")));
                        c.put("orderStats", stats);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", total == null ? 0 : total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("customers", customers);
        return ToolHandler.ok(result);
    }
}
