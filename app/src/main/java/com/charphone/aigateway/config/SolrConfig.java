package com.charphone.aigateway.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedded Solr 9 設定：在同一個 JVM 內啟動 Solr，作為 RAG 向量搜尋引擎。
 * Solr home 目錄從 classpath 複製到可寫位置（WAR 部署環境無法直接寫 classpath）。
 */
@Configuration
public class SolrConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SolrConfig.class);
    private static final String CORE_NAME = "rag";
    private static final String SOLR_RESOURCE_BASE = "solrhome/" + CORE_NAME;

    @Value("${gateway.solr.home:./solrhome}")
    private String solrHomePath;

    private EmbeddedSolrServer solrServer;

    @Bean
    public EmbeddedSolrServer embeddedSolrServer() throws IOException {
        Path solrHome = Path.of(solrHomePath).toAbsolutePath();
        Path coreDir = solrHome.resolve(CORE_NAME);
        Path confDir = coreDir.resolve("conf");

        Files.createDirectories(confDir);
        Files.createDirectories(coreDir.resolve("data"));

        copyResource(solrHome.resolve("solr.xml"), "solrhome/solr.xml");
        copyResourceIfMissing(confDir.resolve("solrconfig.xml"), SOLR_RESOURCE_BASE + "/conf/solrconfig.xml");
        copyResourceIfMissing(confDir.resolve("schema.xml"), SOLR_RESOURCE_BASE + "/conf/schema.xml");
        copyResourceIfMissing(coreDir.resolve("core.properties"), SOLR_RESOURCE_BASE + "/core.properties");

        System.setProperty("solr.install.dir", solrHome.toString());
        log.info("Solr home: {}", solrHome);

        solrServer = new EmbeddedSolrServer(solrHome, CORE_NAME);
        log.info("Embedded Solr 已啟動，core={}", CORE_NAME);
        return solrServer;
    }

    @Override
    public void destroy() {
        if (solrServer != null) {
            try {
                solrServer.close();
            } catch (Exception e) {
                log.warn("Solr 關閉時發生錯誤: {}", e.getMessage());
            }
            log.info("Embedded Solr 已關閉");
        }
    }

    private void copyResource(Path target, String classpathResource) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                log.warn("Solr 資源不存在: {}", classpathResource);
                return;
            }
            Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已複製 Solr 設定: {} → {}", classpathResource, target);
        }
    }

    private void copyResourceIfMissing(Path target, String classpathResource) throws IOException {
        if (Files.exists(target)) return;
        copyResource(target, classpathResource);
    }
}
