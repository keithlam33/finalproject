package com.bootcamp.project_data_provider.exception;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExceptionDTO {
    private int code;
    private String message;
}
