package com.bootcamp.project_data_provider.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {
  @Bean
  RestTemplate restTemplate()  {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add((request, body, execution) -> { // 加一個「攔截器」，即係每一次用呢個 RestTemplate 發 request，都先經過呢段 code
    HttpHeaders headers = request.getHeaders();
    headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
    headers.set("Accept", "application/json");
    headers.set("Accept-Language", "en-US,en;q=0.9");
    headers.set("Referer", "https://finance.yahoo.com/");
    headers.set("Connection", "keep-alive");

    // Optional: if you want cookie here, you can fetch from session manager
    // but you need access to it in config. Otherwise set cookie in service.
    return execution.execute(request, body);
  });
  return rt;
  } 
}
