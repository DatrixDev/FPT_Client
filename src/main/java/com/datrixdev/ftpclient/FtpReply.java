package com.datrixdev.ftpclient;

/** One complete reply from the FTP control connection (RFC 959). */
public record FtpReply(int code, String message) {
    public boolean isPositiveCompletion() { return code >= 200 && code < 300; }
    public boolean isPositivePreliminary() { return code >= 100 && code < 200; }
    public boolean isPositiveIntermediate() { return code >= 300 && code < 400; }
    @Override public String toString() { return code + " " + message; }
}