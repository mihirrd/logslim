package com.logslim.query;

public record FrequencyBucket(
    long bucketNum,
    long timestamp,
    long frequency,
    Double changeFromPrevious
) {}
