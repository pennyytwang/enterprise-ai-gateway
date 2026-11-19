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
 * query_sales_summary：營收彙總（總營收/按月旺季/客戶產品排行）。
 * 營收口徑=SUM(total_amount)。僅系統管理者。
 */
@Component
public class QuerySalesSummaryTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QuerySalesSummaryTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_sales_summary";
    }

    @Override
    public String description() {
        return "營收彙總分析。不帶 groupBy=總營收；groupBy=month 看各月營收（旺季分析）；groupBy=customer/product 看排行（可配 topN 取前幾名）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of("dateFrom", "dateTo", "groupBy"),
                Map.of(
                        "dateFrom", ToolHandler.prop("STRING", "起始日（依 order_date），YYYY-MM-DD"),
                        "dateTo", ToolHandler.prop("STRING", "結束日（依 order_date），YYYY-MM-DD"),
                        "groupBy", ToolHandler.enumProp(
                                List.of("none", "month", "customer", "product"),
                                "none=總營收；month=按月（旺季）；customer=依客戶；product=依產品"),
                        "topN", ToolHandler.prop("INTEGER", "groupBy=customer/product 時取前 N 名（預設 5，最大 50）"),
                        "customerId", ToolHandler.prop("INTEGER", "選擇性：只看單一客戶")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        LocalDate dateFrom = LocalDate.parse(ToolHandler.strArg(args, "dateFrom"));
        LocalDate dateTo = LocalDate.parse(ToolHandler.strArg(args, "dateTo"));
        if (dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE: dateFrom 不可晚於 dateTo");
        }
        if (ChronoUnit.DAYS.between(dateFrom, dateTo) > 366 * 2) {
            throw new IllegalArgumentException("DATE_RANGE_TOO_LARGE: 查詢區間上限 2 年，請縮小日期範圍");
        }
        String groupBy = ToolHandler.strArg(args, "groupBy");
        int topN = Math.min(Math.max(ToolHandler.intArg(args, "topN", 5), 1), 50);
        Integer customerId = ToolHandler.intArgOrNull(args, "customerId");

        StringBuilder where = new StringBuilder("WHERE o.order_date >= ? AND o.order_date <= ? ");
        List<Object> params = new ArrayList<>(List.of(dateFrom, dateTo));
        if (customerId != null) {
            where.append("AND o.customer_id = ? ");
            params.add(customerId);
        }
        String baseWhere = where.toString();

        Map<String, Object> totalRow = mydbJdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS order_count, COALESCE(SUM(o.total_amount), 0) AS revenue "
                        + "FROM orders o " + baseWhere,
                params.toArray());
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("orderCount", QueryOrdersTool.intVal(totalRow.get("order_count")));
        total.put("revenue", totalRow.get("revenue"));

        List<Map<String, Object>> groups = new ArrayList<>();
        String groupSql;
        switch (groupBy) {
            case "month" -> groupSql = """
                    SELECT DATE_FORMAT(o.order_date, '%Y-%m') AS gkey,
                           COUNT(*) AS order_count, SUM(o.total_amount) AS revenue
                    FROM orders o
                    """ + baseWhere
                    + "GROUP BY DATE_FORMAT(o.order_date, '%Y-%m') ORDER BY gkey";
            case "customer" -> groupSql = """
                    SELECT c.customer_name AS gkey,
                           COUNT(*) AS order_count, SUM(o.total_amount) AS revenue
                    FROM orders o JOIN customers c ON c.id = o.customer_id
                    """ + baseWhere
                    + "GROUP BY c.id, c.customer_name ORDER BY revenue DESC LIMIT " + topN;
            case "product" -> groupSql = """
                    SELECT p.product_name AS gkey,
                           COUNT(*) AS order_count, SUM(o.total_amount) AS revenue
                    FROM orders o JOIN products p ON p.id = o.product_id
                    """ + baseWhere
                    + "GROUP BY p.id, p.product_name ORDER BY revenue DESC LIMIT " + topN;
            default -> groupSql = null;
        }
        if (groupSql != null) {
            for (Map<String, Object> row : mydbJdbcTemplate.queryForList(groupSql, params.toArray())) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("key", row.get("gkey"));
                g.put("label", row.get("gkey"));
                g.put("orderCount", QueryOrdersTool.intVal(row.get("order_count")));
                g.put("revenue", row.get("revenue"));
                groups.add(g);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", Map.of("dateFrom", dateFrom.toString(), "dateTo", dateTo.toString()));
        result.put("total", total);
        result.put("groups", groups);
        return ToolHandler.ok(result);
    }
}
