package com.bootcamp.project_stock_data.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum SysEx {
  PARAM_NOT_MATCH(HttpStatus.BAD_REQUEST, 92000, "Parameters Not Match."),
  DATA_PROVIDER_REJECTED_REQUEST(HttpStatus.BAD_GATEWAY, 92001, "Data-provider rejected request."),
  DATA_PROVIDER_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, 92002, "Data-provider API call failed."),
  DATA_PROVIDER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, 92003, "Data-provider response invalid."),
  THREAD_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, 92004, "Thread interrupted during data-provider processing."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 99999, "Internal Server Error.");

  private final HttpStatus httpStatus;
  private final int code;
  private final String message;

  SysEx(HttpStatus httpStatus, int code, String message) {
    this.httpStatus = httpStatus;
    this.code = code;
    this.message = message;
  }
}
