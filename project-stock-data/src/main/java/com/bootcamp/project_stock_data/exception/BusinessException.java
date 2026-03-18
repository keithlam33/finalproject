package com.bootcamp.project_stock_data.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
  private final SysEx sysEx;

  public BusinessException(SysEx sysEx) {
    super(sysEx.getMessage());
    this.sysEx = sysEx;
  }

  public BusinessException(SysEx sysEx, String overrideMessage) {
    super(overrideMessage);
    this.sysEx = sysEx;
  }
}
