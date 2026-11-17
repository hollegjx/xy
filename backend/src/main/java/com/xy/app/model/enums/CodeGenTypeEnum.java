package com.xy.app.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CodeGenTypeEnum {

    HTML("html"),
    MULTI_FILE("multi_file"),
    VUE_PROJECT("vue_project");

    private final String value;

    CodeGenTypeEnum(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CodeGenTypeEnum fromValue(String value) {
        for (CodeGenTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("生成模式仅支持 html、multi_file 或 vue_project");
    }
}
