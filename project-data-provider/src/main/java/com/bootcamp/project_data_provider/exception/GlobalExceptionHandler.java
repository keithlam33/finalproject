package com.bootcamp.project_data_provider.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(value = IllegalArgumentException.class)
  public ExceptionDTO handleIae(IllegalArgumentException e) {
    return ExceptionDTO.builder()
        .code(SysEx.PARAM_NOT_MATCH.getCode())
        .message(SysEx.PARAM_NOT_MATCH.getMessage() + " " + e.getMessage())
        .build();
  }

  @ExceptionHandler(value = RestClientException.class)
  public ExceptionDTO handleRce(RestClientException e) {
    return ExceptionDTO.builder()
        .code(SysEx.REST_CLIENT_EX.getCode())
        .message(SysEx.REST_CLIENT_EX.getMessage() + " " + e.getMessage())
        .build();
   }
}
