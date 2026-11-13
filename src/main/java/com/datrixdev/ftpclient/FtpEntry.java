package com.datrixdev.ftpclient;

/** An entry returned by MLSD or LIST. */
public record FtpEntry(String name, boolean directory, long size, String modified) {
    public String typeLabel() { return directory ? "Thư mục" : "Tệp"; }
    public String sizeLabel() {
        if (directory || size < 0) return "—";
        if (size < 1024) return size + " B";
        final String[] units = {"KB", "MB", "GB", "TB"};
        double number = size;
        int unit = -1;
        do { number /= 1024.0; unit++; } while (number >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", number, units[unit]);
    }
}

