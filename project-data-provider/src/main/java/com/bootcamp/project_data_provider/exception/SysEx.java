package com.bootcamp.project_data_provider.exception;

import lombok.Getter;

@Getter
public enum SysEx {
    PARAM_NOT_MATCH(90000, "Parameters Not Match."),
  REST_CLIENT_EX(90001, "Rest Client API Call.");

  private  int code;
  private String message;

  SysEx(int code, String message) {
    this.code = code;
    this.message = message;
  }
}
