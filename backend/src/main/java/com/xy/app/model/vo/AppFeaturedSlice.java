package com.xy.app.model.vo;

import java.util.List;

public record AppFeaturedSlice(List<AppFeaturedVO> records, boolean hasNext) {
}
