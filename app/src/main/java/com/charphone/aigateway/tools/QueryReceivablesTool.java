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
 * query_receivables：應收帳款（已出貨未收款、逾期未收、未開帳）。
 * 僅系統管理者。
 */
@Component
public class QueryReceivablesTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryReceivablesTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_receivables";
    }

    @Override
    public String description() {
        return "查詢應收帳款狀況：已開帳未收款、逾期未收、已出貨但未開帳。逾期=發票到期日已過且未全額收款。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of("scope"),
                Map.of(
                        "scope", ToolHandler.enumProp(
                                List.of("ALL", "UNPAID", "OVERDUE", "UNINVOICED"),
                                "ALL=全部帳務狀態；UNPAID=已開帳未收款；OVERDUE=逾期未收；UNINVOICED=已出貨但未開帳"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：客戶 id，不帶查全部"),
                        "overdueDays", ToolHandler.prop("INTEGER", "選擇性：至少逾期 N 天（scope=OVERDUE 時用，預設 1）")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        String scope = ToolHandler.strArg(args, "scope");
        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");
        int overdueDays = Math.max(ToolHandler.intArg(args, "overdueDays", 1), 1);

        List<Map<String, Object>> receivables = new ArrayList<>();
        int totalInvoices = 0;
        int overdueInvoices = 0;
        java.math.BigDecimal overdueAmount = java.math.BigDecimal.ZERO;

        if (!"UNINVOICED".equals(scope)) {
            StringBuilder where = new StringBuilder(
                    "WHERE i.payment_status = 0 AND i.due_date IS NOT NULL ");
            List<Object> params = new ArrayList<>();
            if ("OVERDUE".equals(scope)) {
                where.append("AND i.due_date < CURDATE() AND DATEDIFF(CURDATE(), i.due_date) >= ? ");
                params.add(overdueDays);
            }
            if (customerId != null) {
                where.append("AND c.id = ? ");
                params.add(customerId);
            }

            String sql = """
                    SELECT i.invoice_number, i.issue_date, i.due_date, i.amount,
                           i.received_amount, i.payment_status,
                           c.id AS customer_id, c.customer_number, c.customer_name,
                           o.id AS order_id, o.order_number,
                           GREATEST(DATEDIFF(CURDATE(), i.due_date), 0) AS overdue_days
                    FROM invoices i
                    JOIN customers c ON c.id = i.customer_id
                    JOIN orders o ON o.id = i.order_id
                    """ + where + " ORDER BY i.due_date";

            List<Map<String, Object>> rows = mydbJdbcTemplate.queryForList(sql, params.toArray());
            totalInvoices = rows.size();
            for (Map<String, Object> row : rows) {
                java.math.BigDecimal amount = row.get("amount") instanceof Number n
                        ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;
                java.math.BigDecimal received = row.get("received_amount") instanceof Number n
                        ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;
                int od = QueryOrdersTool.intVal(row.get("overdue_days"));
                boolean overdue = od > 0;
                if (overdue) {
                    overdueInvoices++;
                    overdueAmount = overdueAmount.add(amount.subtract(received).max(java.math.BigDecimal.ZERO));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("invoiceNumber", row.get("invoice_number"));
                m.put("issueDate", String.valueOf(row.get("issue_date")));
                m.put("dueDate", String.valueOf(row.get("due_date")));
                m.put("overdueDays", od);
                m.put("amount", row.get("amount"));
                m.put("receivedAmount", row.get("received_amount"));
                m.put("outstandingAmount", amount.subtract(received).max(java.math.BigDecimal.ZERO));
                m.put("paymentStatus", row.get("payment_status"));

                Map<String, Object> cust = new LinkedHashMap<>();
                cust.put("id", row.get("customer_id"));
                cust.put("customerNumber", row.get("customer_number"));
                cust.put("customerName", row.get("customer_name"));
                m.put("customer", cust);

                Map<String, Object> order = new LinkedHashMap<>();
                order.put("orderId", row.get("order_id"));
                order.put("orderNumber", row.get("order_number"));
                m.put("order", order);

                receivables.add(m);
            }
        }

        int uninvoicedCount = 0;
        java.math.BigDecimal uninvoicedAmount = java.math.BigDecimal.ZERO;
        if ("ALL".equals(scope) || "UNINVOICED".equals(scope)) {
            StringBuilder having = new StringBuilder("");
            List<Object> params = new ArrayList<>();
            if (customerId != null) {
                having.append("AND o.customer_id = ? ");
                params.add(customerId);
            }
            String sql = """
                    SELECT o.id AS order_id, o.order_number, c.customer_number, c.customer_name,
                           COALESCE(COALESCE(o.actual_amount, o.total_amount), 0)
                             - COALESCE(inv.invoiced, 0) AS remaining_to_invoice
                    FROM orders o
                    JOIN customers c ON c.id = o.customer_id
                    LEFT JOIN (SELECT order_id, SUM(amount) AS invoiced FROM invoices GROUP BY order_id) inv
                      ON inv.order_id = o.id
                    WHERE EXISTS (SELECT 1 FROM deliveries d WHERE d.order_id = o.id AND d.status = 1)
                      AND COALESCE(inv.invoiced, 0) < COALESCE(COALESCE(o.actual_amount, o.total_amount), 0)
                    """ + having + "ORDER BY o.order_number";

            for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
                uninvoicedCount++;
                java.math.BigDecimal remaining = row.get("remaining_to_invoice") instanceof Number n
                        ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;
                uninvoicedAmount = uninvoicedAmount.add(remaining.max(java.math.BigDecimal.ZERO));

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("invoiceNumber", null);
                m.put("uninvoicedOrder", true);
                m.put("outstandingAmount", remaining.max(java.math.BigDecimal.ZERO));
                Map<String, Object> cust = new LinkedHashMap<>();
                cust.put("customerNumber", row.get("customer_number"));
                cust.put("customerName", row.get("customer_name"));
                m.put("customer", cust);
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("orderId", row.get("order_id"));
                order.put("orderNumber", row.get("order_number"));
                m.put("order", order);
                receivables.add(m);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalInvoices", totalInvoices);
        summary.put("overdueInvoices", overdueInvoices);
        summary.put("overdueAmount", overdueAmount);
        summary.put("uninvoicedCount", uninvoicedCount);
        summary.put("uninvoicedAmount", uninvoicedAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("summary", summary);
        result.put("receivables", receivables);
        return ToolHandler.ok(result);
    }
}
