package com.charphone.aigateway.tools;

import java.math.BigDecimal;
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
 * query_orders：訂單查詢（含明細、出貨、發票）。
 * 工具規格：
 * - orders 為「單產品」明細表，同 order_number 多列需彙總
 * - 生產/出貨/收款狀態一律推導，不讀 orders.status
 */
@Component
public class QueryOrdersTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryOrdersTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_orders";
    }

    @Override
    public String description() {
        return "查詢某段日期區間內的訂單詳細資料。每個訂單可能有多個產品明細列，回覆時建議依 order_number 彙整呈現。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of("dateFrom", "dateTo"),
                Map.of(
                        "dateFrom", ToolHandler.prop("STRING", "查詢區間起始日（依 order_date），格式 YYYY-MM-DD，含當日"),
                        "dateTo", ToolHandler.prop("STRING", "查詢區間結束日（依 order_date），格式 YYYY-MM-DD，含當日"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：客戶 id，不帶則查全部客戶"),
                        "orderNumber", ToolHandler.prop("STRING", "選擇性：訂單號碼精確查詢（如 04-000001-202608-001）"),
                        "productionStatus", ToolHandler.enumProp(
                                List.of("NOT_STARTED", "WAITING", "IN_PROGRESS", "COMPLETED"),
                                "選擇性：生產狀態篩選（未立工單/待生產/生產中/已完成）"),
                        "deliveryStatus", ToolHandler.enumProp(
                                List.of("NOT_DELIVERED", "PARTIAL", "DELIVERED"),
                                "選擇性：出貨狀態篩選（未出貨/部分出貨/已出貨）"),
                        "paymentStatus", ToolHandler.enumProp(
                                List.of("UNPAID", "PARTIAL_PAID", "PAID"),
                                "選擇性：收款狀態篩選（未收/部分收/全收）"),
                        "closeStatus", ToolHandler.prop("INTEGER", "選擇性：結案狀態 0=未結案 1=已結案"),
                        "page", ToolHandler.prop("INTEGER", "頁碼（預設 1）"),
                        "pageSize", ToolHandler.prop("INTEGER", "每頁筆數（預設 20，最大 100）")));
    }

    @Override
    @SuppressWarnings("unchecked")
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

        StringBuilder where = new StringBuilder("WHERE o.order_date >= ? AND o.order_date <= ? ");
        List<Object> params = new ArrayList<>(List.of(dateFrom, dateTo));
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
        Integer closeStatus = ToolHandler.intArgOrNull(args, "closeStatus");
        if (closeStatus != null) {
            where.append("AND o.close_status = ? ");
            params.add(closeStatus);
        }

        Long total = mydbJdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT o.order_number) FROM orders o " + where,
                Long.class, params.toArray());

        String sql = """
                SELECT o.id AS order_id, o.order_number, o.order_date, o.close_status, o.price_type,
                       o.customer_id, c.customer_number, c.customer_name,
                       o.product_id, p.product_code, p.product_name,
                       o.quantity, o.unit_weight, o.unit_price, o.total_amount, o.actual_amount,
                       o.delivery_date, o.expected_delivery_date, o.note,
                       (SELECT COUNT(*) FROM productions WHERE order_id = o.id AND status != 3)               AS prod_count,
                       (SELECT COUNT(*) FROM productions WHERE order_id = o.id AND status != 3
                        AND (manufactured_qty <= 0 OR manufactured_qty IS NULL))                              AS prod_waiting,
                       (SELECT COUNT(*) FROM productions WHERE order_id = o.id AND status != 3
                        AND produced_qty > 0 AND manufactured_qty >= produced_qty)                            AS prod_completed,
                       COALESCE((SELECT SUM(quantity) FROM deliveries
                                 WHERE order_id = o.id AND status = 1), 0)                                    AS total_delivered,
                       COALESCE((SELECT SUM(CASE WHEN payment_status = 1 THEN received_amount ELSE 0 END)
                                 FROM invoices WHERE order_id = o.id), 0)                                     AS total_received
                FROM orders o
                JOIN customers c ON c.id = o.customer_id
                JOIN products p ON p.id = o.product_id
                """ + where + " ORDER BY o.order_date DESC, o.order_number LIMIT "
                + pageSize + " OFFSET " + ((page - 1) * pageSize);

        Map<String, Map<String, Object>> orders = new LinkedHashMap<>();
        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            String num = String.valueOf(row.get("order_number"));
            BigDecimal amount = dec(row.get("total_amount"));
            Map<String, Object> order = orders.get(num);
            if (order == null) {
                order = new LinkedHashMap<>();
                int priceType = intVal(row.get("price_type"));
                order.put("orderId", num);
                order.put("orderDate", String.valueOf(row.get("order_date")));
                order.put("closeStatus", intVal(row.get("close_status")));
                order.put("priceType", priceType);
                order.put("unit", priceType == 1 ? "kg" : "pcs");

                StatusDeriver.Labeled prod = StatusDeriver.orderProduction(
                        intVal(row.get("prod_count")), intVal(row.get("prod_waiting")),
                        intVal(row.get("prod_completed")));
                StatusDeriver.Labeled deliv = StatusDeriver.delivery(
                        dec(row.get("total_delivered")), dec(row.get("quantity")));
                StatusDeriver.Labeled pay = StatusDeriver.payment(
                        dec(row.get("total_received")), amount);
                order.put("productionStatus", prod.code());
                order.put("productionStatusLabel", prod.label());
                order.put("deliveryStatus", deliv.code());
                order.put("deliveryStatusLabel", deliv.label());
                order.put("paymentStatus", pay.code());
                order.put("paymentStatusLabel", pay.label());

                Map<String, Object> customer = new LinkedHashMap<>();
                customer.put("id", row.get("customer_id"));
                customer.put("customerNumber", row.get("customer_number"));
                customer.put("customerName", row.get("customer_name"));
                order.put("customer", customer);

                order.put("expectedDeliveryDate", row.get("expected_delivery_date") == null
                        ? null : String.valueOf(row.get("expected_delivery_date")));
                order.put("deliveryDate", row.get("delivery_date") == null
                        ? null : String.valueOf(row.get("delivery_date")));
                order.put("totalAmount", row.get("total_amount"));
                order.put("actualAmount", row.get("actual_amount"));
                order.put("note", row.get("note"));
                order.put("items", new ArrayList<Map<String, Object>>());
                order.put("_orderId", intVal(row.get("order_id")));
                orders.put(num, order);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.get("product_id"));
            item.put("productCode", row.get("product_code"));
            item.put("productName", row.get("product_name"));
            item.put("quantity", row.get("quantity"));
            item.put("unitWeight", row.get("unit_weight"));
            item.put("unitPrice", row.get("unit_price"));
            item.put("lineAmount", row.get("total_amount"));
            ((List<Map<String, Object>>) order.get("items")).add(item);
        }

        Map<String, Map<String, Object>> filtered = filterByDerivedStatus(orders, args);
        attachDetails(filtered);

        filtered.values().forEach(o -> ((Map<String, Object>) o).remove("_orderId"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("matched", filtered.size());
        result.put("orders", filtered.values());
        return ToolHandler.ok(result);
    }

    static BigDecimal dec(Object v) {
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }

    static int intVal(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> filterByDerivedStatus(
            Map<String, Map<String, Object>> orders, Map<String, Object> args) {
        String prod = ToolHandler.strArgOrNull(args, "productionStatus");
        String deliv = ToolHandler.strArgOrNull(args, "deliveryStatus");
        String pay = ToolHandler.strArgOrNull(args, "paymentStatus");
        if (prod == null && deliv == null && pay == null) {
            return orders;
        }
        Map<String, Map<String, Object>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : orders.entrySet()) {
            Map<String, Object> o = e.getValue();
            if (prod != null && !prod.equals(o.get("productionStatus"))) {
                continue;
            }
            if (deliv != null && !deliv.equals(o.get("deliveryStatus"))) {
                continue;
            }
            if (pay != null && !pay.equals(o.get("paymentStatus"))) {
                continue;
            }
            filtered.put(e.getKey(), o);
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private void attachDetails(Map<String, Map<String, Object>> orders) {
        for (Map<String, Object> order : orders.values()) {
            int orderId = (Integer) order.remove("_orderId");

            List<Map<String, Object>> deliveries = mydbJdbcTemplate.queryForList(
                    "SELECT delivery_number, delivery_date, quantity, status, note "
                            + "FROM deliveries WHERE order_id = ? AND status = 1 ORDER BY delivery_date",
                    orderId);
            List<Map<String, Object>> deliveryList = new ArrayList<>();
            for (Map<String, Object> d : deliveries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deliveryNumber", d.get("delivery_number"));
                m.put("deliveryDate", d.get("delivery_date") == null ? null : String.valueOf(d.get("delivery_date")));
                m.put("quantity", d.get("quantity"));
                m.put("status", d.get("status"));
                m.put("note", d.get("note"));
                deliveryList.add(m);
            }
            order.put("deliveries", deliveryList);

            List<Map<String, Object>> invoices = mydbJdbcTemplate.queryForList(
                    "SELECT invoice_number, amount, issue_date, due_date, status, payment_status, "
                            + "payment_date, received_amount, note "
                            + "FROM invoices WHERE order_id = ? ORDER BY issue_date",
                    orderId);
            List<Map<String, Object>> invoiceList = new ArrayList<>();
            for (Map<String, Object> inv : invoices) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("invoiceNumber", inv.get("invoice_number"));
                m.put("amount", inv.get("amount"));
                m.put("issueDate", inv.get("issue_date") == null ? null : String.valueOf(inv.get("issue_date")));
                m.put("dueDate", inv.get("due_date") == null ? null : String.valueOf(inv.get("due_date")));
                m.put("status", inv.get("status"));
                m.put("paymentStatus", inv.get("payment_status"));
                m.put("paymentDate", inv.get("payment_date") == null ? null : String.valueOf(inv.get("payment_date")));
                m.put("receivedAmount", inv.get("received_amount"));
                m.put("note", inv.get("note"));
                invoiceList.add(m);
            }
            order.put("invoices", invoiceList);
        }
    }
}
