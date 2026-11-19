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
 * query_inventory：庫存查詢（各倉分布、低於安全庫存警示、成本價）。
 * 僅系統管理者。
 */
@Component
public class QueryInventoryTool implements ToolHandler {

    private final JdbcTemplate mydbJdbcTemplate;

    public QueryInventoryTool(@Qualifier("mydbJdbcTemplate") JdbcTemplate mydbJdbcTemplate) {
        this.mydbJdbcTemplate = mydbJdbcTemplate;
    }

    @Override
    public String name() {
        return "query_inventory";
    }

    @Override
    public String description() {
        return "查詢庫存。可依品項類型/倉庫/關鍵字過濾；lowStockOnly=true 只回低於安全庫存的品項警示。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of(),
                Map.of(
                        "keyword", ToolHandler.prop("STRING", "選擇性：品項代碼或名稱關鍵字"),
                        "itemType", ToolHandler.prop("INTEGER", "選擇性：1=原料, 2=半成品, 3=成品"),
                        "warehouseId", ToolHandler.prop("INTEGER", "選擇性：只看某倉庫，不帶看全部倉庫"),
                        "lowStockOnly", ToolHandler.prop("BOOLEAN", "true=只回庫存量低於安全庫存的品項（預設 false）")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        String keyword = ToolHandler.strArgOrNull(args, "keyword");
        Integer itemType = ToolHandler.intArgOrNull(args, "itemType");
        Integer warehouseId = ToolHandler.intArgOrNull(args, "warehouseId");
        boolean lowStockOnly = "true".equalsIgnoreCase(String.valueOf(args.get("lowStockOnly")));

        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (itemType != null) {
            where.append("AND i.item_type = ? ");
            params.add(itemType);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND (i.item_code LIKE ? OR i.item_name LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }

        String sql = """
                SELECT i.id, i.item_code, i.item_name, i.item_type, i.unit, i.unit_cost,
                       i.safety_stock, i.status, COALESCE(SUM(s.quantity), 0) AS total_quantity
                FROM inventory_items i
                LEFT JOIN inventory_stock s ON s.item_id = i.id
                """ + where + """
                GROUP BY i.id, i.item_code, i.item_name, i.item_type, i.unit, i.unit_cost,
                         i.safety_stock, i.status
                ORDER BY i.item_code""";

        List<Map<String, Object>> items = new ArrayList<>();
        int lowStockCount = 0;
        for (Map<String, Object> row : mydbJdbcTemplate.queryForList(sql, params.toArray())) {
            java.math.BigDecimal totalQty = row.get("total_quantity") instanceof Number n
                    ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;
            java.math.BigDecimal safety = row.get("safety_stock") instanceof Number n
                    ? java.math.BigDecimal.valueOf(n.doubleValue()) : java.math.BigDecimal.ZERO;
            boolean low = safety.signum() > 0 && totalQty.compareTo(safety) < 0;
            if (lowStockOnly && !low) {
                continue;
            }
            if (low) {
                lowStockCount++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", row.get("id"));
            m.put("itemCode", row.get("item_code"));
            m.put("itemName", row.get("item_name"));
            m.put("itemType", row.get("item_type"));
            m.put("itemTypeLabel", StatusDeriver.itemTypeLabel(
                    QueryOrdersTool.intVal(row.get("item_type"))));
            m.put("unit", row.get("unit"));
            m.put("unitCost", row.get("unit_cost"));
            m.put("safetyStock", row.get("safety_stock"));
            m.put("totalQuantity", row.get("total_quantity"));
            m.put("lowStock", low);
            m.put("status", row.get("status"));
            items.add(m);
        }

        // 各倉庫分布
        if (!items.isEmpty()) {
            List<Object> ids = new ArrayList<>();
            items.forEach(i -> ids.add(((Map<String, Object>) i).get("itemId")));
            String inClause = ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("'0'");
            StringBuilder stockSql = new StringBuilder(
                    "SELECT s.item_id, w.id AS warehouse_id, w.warehouse_code, w.warehouse_name, s.quantity "
                            + "FROM inventory_stock s JOIN warehouses w ON w.id = s.warehouse_id "
                            + "WHERE s.item_id IN (" + inClause);
            List<Object> stockParams = new ArrayList<>(ids);
            if (warehouseId != null) {
                stockSql.append(" AND w.id = ?");
                stockParams.add(warehouseId);
            }
            for (Map<String, Object> srow : mydbJdbcTemplate.queryForList(stockSql.toString(), stockParams.toArray())) {
                for (Map<String, Object> item : items) {
                    if (QueryOrdersTool.intVal(srow.get("item_id")) == QueryOrdersTool.intVal(item.get("itemId"))) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> stocks = (List<Map<String, Object>>) ((Map<String, Object>) item)
                                .computeIfAbsent("stocks", k -> new ArrayList<Map<String, Object>>());
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("warehouseId", srow.get("warehouse_id"));
                        sm.put("warehouseCode", srow.get("warehouse_code"));
                        sm.put("warehouseName", srow.get("warehouse_name"));
                        sm.put("quantity", srow.get("quantity"));
                        stocks.add(sm);
                    }
                }
            }
            items.forEach(i -> ((Map<String, Object>) i).putIfAbsent("stocks", new ArrayList<>()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("lowStockCount", lowStockCount);
        result.put("items", items);
        return ToolHandler.ok(result);
    }
}
