package main.java.com.bootcamp.final_project.controller.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.final_project.controller.ProfileOperation;
import com.bootcamp.final_project.model.dto.ProfileDTO;
import com.bootcamp.final_project.service.ProfileService;
@RestController
public class ProfileController implements ProfileOperation{
  @Autowired
  private ProfileService profileService;

  @Override
  public ProfileDTO getStock(String symbol) {
    return this.profileService.getProfile(symbol);
  }
  @Override
  public List<ProfileDTO> getProfiles(List<String> symbols) {
    return this.profileService.getProfiles(symbols);
  }
}
