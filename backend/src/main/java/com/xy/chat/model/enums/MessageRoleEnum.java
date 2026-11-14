package com.xy.chat.model.enums;

public enum MessageRoleEnum {

    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRoleEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
