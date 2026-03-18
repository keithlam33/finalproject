package com.bootcamp.project_data_provider.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum SysEx {
  PARAM_NOT_MATCH(HttpStatus.BAD_REQUEST, 90000, "Parameters Not Match."),
  YAHOO_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, 90001, "Yahoo API call failed."),
  YAHOO_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, 90002, "Yahoo response invalid or deserialization failed."),
  FINNHUB_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, 90003, "Finnhub API call failed."),
  FINNHUB_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, 90004, "Finnhub response invalid or deserialization failed."),
  THREAD_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, 90005, "Thread interrupted during external API processing."),
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
