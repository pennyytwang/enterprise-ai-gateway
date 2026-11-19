package com.charphone.aigateway.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.ModelRouter;
import com.charphone.aigateway.service.UsageReportService;

/**
 * 用量報表 API：依模型/日期彙總使用量，附 Model Router 即時統計。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageReportController {

    private final UsageReportService usageReportService;
    private final LlmProvider provider;

    public UsageReportController(UsageReportService usageReportService, LlmProvider provider) {
        this.usageReportService = usageReportService;
        this.provider = provider;
    }

    @GetMapping("/report")
    public Map<String, Object> report(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byModel", usageReportService.reportByModel());
        result.put("byDay", usageReportService.reportByDay(days));

        if (provider instanceof ModelRouter router) {
            result.put("routerStats", router.getStats());
        } else {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("name", provider.name());
            single.put("note", "單供應商模式，無 failover");
            result.put("routerStats", single);
        }
        return result;
    }
}
