package com.charphone.aigateway.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 雙資料源：aigateway（主，稽核/設定）與 mydb（企業 ERP 資料，唯讀）。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties gatewayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public HikariDataSource gatewayDataSource() {
        return gatewayDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("mydb.datasource")
    public DataSourceProperties mydbDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public HikariDataSource mydbDataSource() {
        return mydbDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public JdbcTemplate gatewayJdbcTemplate(@Qualifier("gatewayDataSource") DataSource gatewayDataSource) {
        return new JdbcTemplate(gatewayDataSource);
    }

    @Bean
    public JdbcTemplate mydbJdbcTemplate(@Qualifier("mydbDataSource") DataSource mydbDataSource) {
        return new JdbcTemplate(mydbDataSource);
    }
}
