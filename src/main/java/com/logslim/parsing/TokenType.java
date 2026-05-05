package com.logslim.parsing;

public enum TokenType {
    STATIC,   // alphabetic word — part of the template
    DYNAMIC   // number, UUID, hash, timestamp — becomes a parameter
}
