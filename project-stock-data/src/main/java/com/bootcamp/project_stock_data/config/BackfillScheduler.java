package com.bootcamp.project_stock_data.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bootcamp.project_stock_data.service.BackfillService;

@Component
public class BackfillScheduler {

  @Autowired
  private BackfillService backfillService;

  // After market close: run maintenance backfill and run initial for any new symbols.
  @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "America/New_York")
  public void runBackfillJobs() {
    backfillService.checkAndBackfill();
  }
}

