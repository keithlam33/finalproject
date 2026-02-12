package main.java.com.bootcamp.final_project.service;

import java.util.List;

import com.bootcamp.final_project.model.dto.ProfileDTO;

public interface ProfileService {
  
  ProfileDTO getProfile(String symbol);

  List<ProfileDTO> getProfiles(List<String> symbol);

}
