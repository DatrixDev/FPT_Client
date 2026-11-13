package com.datrixdev.ftpclient;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.regex.*;

/**
 * A small, dependency-free, passive-mode FTP client for a teaching project.
 * Control: TCP, commands/replies. Transfers: separate passive TCP sockets.
 * Not thread safe: callers must serialize all operations on a session.
 * IMPORTANT: Classic FTP transmits credentials/data unencrypted. Use in a lab.
 */
public final class FtpSession implements AutoCloseable {
    private static final int TIMEOUT_MS = 20_000;
    private static final Pattern PASV = Pattern.compile("\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)");
    private static final Pattern EPSV = Pattern.compile("\\((.)\\1\\1(\\d+)\\1\\)");
    private static final Pattern UNIX = Pattern.compile("^([bcdlps-][rwxstST-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\d+\\s+(?:\\d+:\\d+|\\d{4})\\s+(.+)$");
    private final Socket control;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private boolean closed;

    private FtpSession(Socket socket) throws IOException {
        control = socket;
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static FtpSession connect(String host, int port, String user, char[] password) throws IOException {
        if (host == null || host.isBlank()) throw new IOException("Cần nhập địa chỉ máy chủ.");
        if (port < 1 || port > 65535) throw new IOException("Port phải từ 1 đến 65535.");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host.trim(), port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            FtpSession ftp = new FtpSession(socket);
            ftp.requireCode(ftp.readReply(), 220);
            FtpReply login = ftp.command("USER " + safeArg(user));
            if (login.code() == 331) {
                login = ftp.command("PASS " + safeArg(new String(password)));
            }
            ftp.requireCode(login, 230);
            ftp.requireCode(ftp.command("TYPE I"), 200);
            return ftp;
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static String safeArg(String text) throws IOException {
        if (text == null || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
            throw new IOException("Tham số FTP không hợp lệ.");
        }
        return text;
    }

    private FtpReply readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) throw new EOFException("Máy chủ đã ngắt kết nối.");
        if (line.length() < 3 || !Character.isDigit(line.charAt(0)) ||
                !Character.isDigit(line.charAt(1)) || !Character.isDigit(line.charAt(2))) {
            throw new IOException("Phản hồi FTP không hợp lệ: " + line);
        }
        int code = Integer.parseInt(line.substring(0, 3));
        StringBuilder message = new StringBuilder(line.length() > 4 ? line.substring(4) : "");
        if (line.length() > 3 && line.charAt(3) == '-') {
            String terminator = line.substring(0, 3) + " ";
            int count = 0;
            do {
                if (++count > 500) throw new IOException("Phản hồi FTP quá dài.");
                line = reader.readLine();
                if (line == null) throw new EOFException("Mất kết nối khi đọc phản hồi FTP.");
                message.append('\n').append(line);
            } while (!line.startsWith(terminator));
        }
        return new FtpReply(code, message.toString());
    }

    private FtpReply command(String text) throws IOException {
        if (closed) throw new IOException("Chưa kết nối đến FTP Server.");
        writer.write(safeArg(text));
        writer.write("\r\n");
        writer.flush();
        return readReply();
    }

    private void requireCode(FtpReply reply, int... allowed) throws IOException {
        for (int code : allowed) if (reply.code() == code) return;
        throw new IOException("FTP " + reply);
    }

    public String currentDirectory() throws IOException {
        FtpReply reply = command("PWD");
        requireCode(reply, 257);
        Matcher match = Pattern.compile("\"((?:\"\"|[^\"])*)\"").matcher(reply.message());
        return match.find() ? match.group(1).replace("\"\"", "\"") : "/";
    }

    public void changeDirectory(String dir) throws IOException {
        requireCode(command("CWD " + safeArg(dir)), 250);
    }

    public void makeDirectory(String name) throws IOException {
        requireCode(command("MKD " + safeArg(name)), 257, 250);
    }

    public void deleteFile(String name) throws IOException {
        requireCode(command("DELE " + safeArg(name)), 250);
    }

    public void removeDirectory(String name) throws IOException {
        requireCode(command("RMD " + safeArg(name)), 250);
    }

    public void rename(String oldName, String newName) throws IOException {
        requireCode(command("RNFR " + safeArg(oldName)), 350);
        requireCode(command("RNTO " + safeArg(newName)), 250);
    }

    private Socket openPassive() throws IOException {
        int port;
        FtpReply epsv = command("EPSV");
        if (epsv.code() == 229) {
            Matcher m = EPSV.matcher(epsv.message());
            if (!m.find()) throw new IOException("Phản hồi EPSV không hợp lệ: " + epsv);
            port = Integer.parseInt(m.group(2));
        } else if (epsv.code() >= 400) {
            FtpReply pasv = command("PASV");
            requireCode(pasv, 227);
            Matcher m = PASV.matcher(pasv.message());
            if (!m.find()) throw new IOException("Phản hồi PASV không hợp lệ: " + pasv);
            int p1 = Integer.parseInt(m.group(5)), p2 = Integer.parseInt(m.group(6));
            port = p1 * 256 + p2;
        } else {
            throw new IOException("FTP " + epsv);
        }
        if (port < 1 || port > 65535) throw new IOException("Cổng dữ liệu FTP không hợp lệ.");
        // Ignore the PASV-advertised IP: use the same host as the control channel.
        // This works with local/NAT FileZilla and prevents server-initiated host redirects.
        Socket data = new Socket();
        try {
            data.connect(new InetSocketAddress(control.getInetAddress(), port), TIMEOUT_MS);
            data.setSoTimeout(TIMEOUT_MS);
            return data;
        } catch (IOException | RuntimeException ex) {
            try { data.close(); } catch (IOException ignored) { }
            throw ex;
        }
    }

    @FunctionalInterface private interface DataTransfer<T> { T run(Socket data) throws IOException; }

    private <T> T transfer(String command, DataTransfer<T> body) throws IOException {
        try (Socket data = openPassive()) {
            FtpReply start = command(command);
            if (!start.isPositivePreliminary()) {
                if (command.equals("MLSD") && start.code() >= 500)
                    throw new FtpUnsupportedException(start);
                throw new IOException("FTP " + start);
            }
            T result;
            try {
                result = body.run(data);
            } catch (IOException ex) {
                // Server may still send 426 after interrupted transfers. Close session
                // so a stale response cannot desynchronize subsequent commands.
                close();
                throw ex;
            }
            // Data connection has reached EOF before reading the final reply.
            requireCode(readReply(), 226, 250);
            return result;
        }
    }

    private List<String> lines(String ftpCommand) throws IOException {
        return transfer(ftpCommand, data -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    data.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> result = new ArrayList<>();
                String line;
                while ((line = input.readLine()) != null) result.add(line);
                return result;
            }
        });
    }

    public List<FtpEntry> listFiles() throws IOException {
        List<FtpEntry> entries = new ArrayList<>();
        try {
            for (String line : lines("MLSD")) {
                int split = line.indexOf(' ');
                if (split < 0) continue;
                String factText = line.substring(0, split);
                String name = line.substring(split + 1).trim();
                if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                String type = "", modified = "";
                long size = -1;
                for (String fact : factText.split(";")) {
                    int eq = fact.indexOf('=');
                    if (eq == -1) continue;
                    String key = fact.substring(0, eq).toLowerCase(Locale.ROOT);
                    String value = fact.substring(eq + 1);
                    if (key.equals("type")) type = value.toLowerCase(Locale.ROOT);
                    if (key.equals("modify")) modified = value;
                    if (key.equals("size")) {
                        try { size = Long.parseLong(value); } catch (NumberFormatException ignored) { }
                    }
                }
                if (type.equals("cdir") || type.equals("pdir")) continue;
                entries.add(new FtpEntry(name, type.equals("dir"), size, modified));
            }
        } catch (FtpUnsupportedException ex) {
            entries = listUnix();
        }
        entries.sort(Comparator.comparing(FtpEntry::directory).reversed()
                .thenComparing(FtpEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private List<FtpEntry> listUnix() throws IOException {
        List<FtpEntry> items = new ArrayList<>();
        for (String line : lines("LIST")) {
            if (line.startsWith("total ")) continue;
            Matcher m = UNIX.matcher(line);
            if (m.matches()) {
                long size;
                try { size = Long.parseLong(m.group(2)); } catch (NumberFormatException ex) { size = -1; }
                String name = m.group(3);
                if (m.group(1).charAt(0) == 'l' && name.contains(" -> "))
                    name = name.substring(0, name.indexOf(" -> "));
                items.add(new FtpEntry(name, m.group(1).charAt(0) == 'd', size, ""));
            } else {
                // Windows IIS style: 05-17-24  10:45AM  <DIR>  folder
                Matcher win = Pattern.compile("^\\d{2}-\\d{2}-\\d{2,4}\\s+\\d{1,2}:\\d{2}(?:AM|PM)\\s+(<DIR>|\\d+)\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(line);
                if (win.matches()) {
                    boolean directory = win.group(1).equalsIgnoreCase("<DIR>");
                    long size = directory ? -1 : Long.parseLong(win.group(1));
                    items.add(new FtpEntry(win.group(2), directory, size, ""));
                }
            }
        }
        return items;
    }

    public void download(String remoteName, Path destination, LongConsumer progress) throws IOException {
        Path target = destination.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Đường dẫn tải xuống không hợp lệ.");
        Path temp = Files.createTempFile(parent, ".ftp-download-", ".part");
        try {
            transfer("RETR " + safeArg(remoteName), data -> {
                try (InputStream input = data.getInputStream(); OutputStream output = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    long total = 0;
                    while ((n = input.read(buffer)) != -1) {
                        output.write(buffer, 0, n);
                        total += n;
                        if (progress != null) progress.accept(total);
                    }
                }
                return null;
            });
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void upload(Path local, String remoteName, LongConsumer progress) throws IOException {
        if (!Files.isRegularFile(local)) throw new IOException("Tệp tải lên không tồn tại.");
        transfer("STOR " + safeArg(remoteName), data -> {
            try (InputStream input = Files.newInputStream(local); OutputStream output = data.getOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    total += n;
                    if (progress != null) progress.accept(total);
                }
                output.flush();
                data.shutdownOutput();
            }
            return null;
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            writer.write("QUIT\r\n"); writer.flush();
        } catch (IOException ignored) { }
        try { control.close(); } catch (IOException ignored) { }
    }

    private static final class FtpUnsupportedException extends IOException {
        FtpUnsupportedException(FtpReply reply) { super(reply.toString()); }
    }
}
