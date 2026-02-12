package main.java.com.bootcamp.final_project.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.final_project.model.dto.ProfileDTO;
import com.bootcamp.final_project.service.ProfileService;

@Service
public class ProfileServiceimpl implements ProfileService {
  @Value("${stock-api.finnhub.domain}") 
  private String apiDomain; 
  @Value("${stock-api.finnhub.path.profile}") 
  private String profilePath; 
  @Value("${stock-api.key}") 
  private String apiKey;
  
  @Autowired
  private RestTemplate restTemplate;

  @Override
  public ProfileDTO getProfile(String symbol){
    String url = UriComponentsBuilder.newInstance()
      .scheme("https")
      .host(apiDomain)
      .path(profilePath)
      .queryParam("symbol", symbol) 
      .queryParam("token", apiKey) 
      .build() 
      .toUriString();
      return restTemplate.getForObject(url, ProfileDTO.class);
  }


  @Override
  public List<ProfileDTO> getProfiles(List<String> symbols) {
    return symbols.stream()
      .map(s -> getProfile(s))
      .collect(Collectors.toList());
  }
}
