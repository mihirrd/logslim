package com.logslim.query;

import com.logslim.storage.Template;

public record FrequencyDelta(
    Template template,
    long observedCount,
    long baselineCount,
    double changePercent,
    boolean isNew,
    boolean disappeared
) {}
