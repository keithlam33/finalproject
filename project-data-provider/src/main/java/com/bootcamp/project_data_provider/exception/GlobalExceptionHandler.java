package com.bootcamp.project_data_provider.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(value = IllegalArgumentException.class)
  public ResponseEntity<ExceptionDTO> handleIae(IllegalArgumentException e) {
    return ResponseEntity.status(SysEx.PARAM_NOT_MATCH.getHttpStatus())
        .body(ExceptionDTO.builder()
            .code(SysEx.PARAM_NOT_MATCH.getCode())
            .message(SysEx.PARAM_NOT_MATCH.getMessage() + " " + e.getMessage())
            .build());
  }

  @ExceptionHandler(value = BusinessException.class)
  public ResponseEntity<ExceptionDTO> handleBusiness(BusinessException e) {
    SysEx sysEx = e.getSysEx();
    return ResponseEntity.status(sysEx.getHttpStatus())
        .body(ExceptionDTO.builder()
            .code(sysEx.getCode())
            .message(e.getMessage())
            .build());
  }

  @ExceptionHandler(value = Exception.class)
  public ResponseEntity<ExceptionDTO> handleException(Exception e) {
    return ResponseEntity.status(SysEx.INTERNAL_ERROR.getHttpStatus())
        .body(ExceptionDTO.builder()
            .code(SysEx.INTERNAL_ERROR.getCode())
            .message(SysEx.INTERNAL_ERROR.getMessage() + " " + e.getMessage())
            .build());
  }
}
