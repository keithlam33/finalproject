package com.bootcamp.project_stock_data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bootcamp.project_stock_data.service.impl.DataProviderServiceimpl;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
  properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.task.scheduling.enabled=false",
    "logging.level.org.springframework.web.client.RestTemplate=INFO"
  })
public class quotetest {
    @Autowired
  private DataProviderServiceimpl dataProviderService;

  @Test
  void bench_quotes() {
    this.dataProviderService.benchQuotes(10); // 跑 10 次，你想幾多次自己改
  }
}
