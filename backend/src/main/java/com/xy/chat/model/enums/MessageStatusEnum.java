package com.xy.chat.model.enums;

public enum MessageStatusEnum {

    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    MessageStatusEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
