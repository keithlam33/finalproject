package main.java.com.bootcamp.final_project.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bootcamp.final_project.model.dto.ProfileDTO;

public interface ProfileOperation {
  
  @GetMapping(value = "/profile")
  public ProfileDTO getProfile(@RequestParam String symbol);

  @GetMapping(value = "/profiles")
  public List<ProfileDTO> getProfiles(@RequestParam List<String> symbols);
}
