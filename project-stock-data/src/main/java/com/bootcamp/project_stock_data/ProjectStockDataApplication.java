package com.bootcamp.project_stock_data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProjectStockDataApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectStockDataApplication.class, args);
	}

}
