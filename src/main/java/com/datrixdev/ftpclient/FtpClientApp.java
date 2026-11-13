package com.datrixdev.ftpclient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** FTP Client demo UI. The EDT never performs socket I/O. */
public final class FtpClientApp extends JFrame {
    private final JTextField host = new JTextField("127.0.0.1", 13);
    private final JTextField port = new JTextField("21", 5);
    private final JTextField username = new JTextField("demo", 10);
    private final JPasswordField password = new JPasswordField(11);
    private final JButton connect = button("Kết nối");
    private final JButton disconnect = button("Ngắt kết nối");
    private final JButton up = button("↑ Lên một cấp");
    private final JButton reload = button("↻ Làm mới");
    private final JButton upload = button("↑ Upload");
    private final JButton download = button("↓ Download");
    private final JButton mkdir = button("+ Tạo thư mục");
    private final JButton rename = button("Đổi tên");
    private final JButton delete = button("Xóa");
    private final JLabel path = new JLabel("Chưa kết nối");
    private final JLabel status = new JLabel("Sẵn sàng. Hãy khởi động FileZilla Server rồi kết nối.");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Tên", "Loại", "Kích thước", "Sửa đổi (UTC)"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private List<FtpEntry> entries = List.of();
    private FtpSession session;
    private boolean busy;

    private static JButton button(String label) {
        JButton b = new JButton(label);
        b.setFocusPainted(false);
        b.setMargin(new Insets(7, 12, 7, 12));
        return b;
    }

    public FtpClientApp() {
        super("FTP Client - Đồ án Lập trình mạng");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(890, 550));
        setSize(1080, 700);
        setLocationRelativeTo(null);
        setContentPane(buildUI());
        registerActions();
        updateEnabled();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (session != null) session.close(); }
        });
    }

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(16, 18, 14, 18));

        JPanel top = new JPanel(new BorderLayout(0, 12));
        JPanel login = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        login.setBorder(BorderFactory.createTitledBorder("Kết nối tới FTP Server"));
        login.add(new JLabel("Host:")); login.add(host);
        login.add(new JLabel("Port:")); login.add(port);
        login.add(new JLabel("User:")); login.add(username);
        login.add(new JLabel("Password:")); login.add(password);
        login.add(connect); login.add(disconnect);
        top.add(login, BorderLayout.NORTH);

        JPanel navigation = new JPanel(new BorderLayout(12, 0));
        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        navLeft.add(up); navLeft.add(reload);
        navigation.add(navLeft, BorderLayout.WEST);
        path.setFont(path.getFont().deriveFont(Font.BOLD, 13f));
        navigation.add(path, BorderLayout.CENTER);
        top.add(navigation, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        table.setRowHeight(29);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(460);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        root.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(upload); buttons.add(download); buttons.add(mkdir);
        buttons.add(rename); buttons.add(delete);
        bottom.add(buttons, BorderLayout.NORTH);
        JPanel info = new JPanel(new BorderLayout(10, 4));
        progress.setIndeterminate(false);
        progress.setPreferredSize(new Dimension(140, 18));
        info.add(progress, BorderLayout.EAST);
        info.add(status, BorderLayout.CENTER);
        JLabel caution = new JLabel("Lưu ý: FTP thường truyền mật khẩu không mã hóa. Chỉ demo trên localhost hoặc mạng tin cậy.");
        caution.setForeground(new Color(155, 83, 18));
        bottom.add(info, BorderLayout.CENTER);
        bottom.add(caution, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private void registerActions() {
        connect.addActionListener(e -> connectAsync());
        password.addActionListener(e -> connectAsync());
        disconnect.addActionListener(e -> {
            if (session != null) session.close();
            session = null;
            entries = List.of();
            model.setRowCount(0);
            path.setText("Chưa kết nối");
            status.setText("Đã ngắt kết nối.");
            updateEnabled();
        });
        reload.addActionListener(e -> refreshAsync());
        up.addActionListener(e -> changeDirectory(".."));
        upload.addActionListener(e -> uploadSelected());
        download.addActionListener(e -> downloadSelected());
        mkdir.addActionListener(e -> createFolder());
        rename.addActionListener(e -> renameSelected());
        delete.addActionListener(e -> deleteSelected());
        table.getSelectionModel().addListSelectionListener(e -> updateEnabled());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !busy) {
                    FtpEntry item = selected();
                    if (item != null) {
                        if (item.directory()) changeDirectory(item.name());
                        else downloadSelected();
                    }
                }
            }
        });
    }

    private FtpEntry selected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || viewRow >= table.getRowCount()) return null;
        int index = table.convertRowIndexToModel(viewRow);
        return index < entries.size() ? entries.get(index) : null;
    }

    private void updateEnabled() {
        boolean linked = session != null;
        boolean ready = linked && !busy;
        connect.setEnabled(!linked && !busy);
        disconnect.setEnabled(ready);
        up.setEnabled(ready); reload.setEnabled(ready);
        upload.setEnabled(ready); mkdir.setEnabled(ready);
        FtpEntry selected = selected();
        boolean hasSelection = selected != null && ready;
        download.setEnabled(hasSelection && !selected.directory());
        delete.setEnabled(hasSelection); rename.setEnabled(hasSelection);
        host.setEnabled(!linked && !busy); port.setEnabled(!linked && !busy);
        username.setEnabled(!linked && !busy); password.setEnabled(!linked && !busy);
    }

    private record DirectorySnapshot(String directory, List<FtpEntry> files) { }
    private record ConnectionSnapshot(FtpSession ftp, DirectorySnapshot directory) { }

    private DirectorySnapshot snapshot(FtpSession ftp) throws IOException {
        return new DirectorySnapshot(ftp.currentDirectory(), ftp.listFiles());
    }

    private void showDirectory(DirectorySnapshot data) {
        path.setText("Thư mục trên Server:  " + data.directory());
        entries = data.files();
        model.setRowCount(0);
        for (FtpEntry item : entries) {
            model.addRow(new Object[]{(item.directory() ? "📁 " : "📄 ") + item.name(),
                    item.typeLabel(), item.sizeLabel(), item.modified()});
        }
        status.setText("Hiển thị " + entries.size() + " mục trong " + data.directory());
        updateEnabled();
    }

    private void connectAsync() {
        final int portNumber;
        try { portNumber = Integer.parseInt(port.getText().trim()); }
        catch (NumberFormatException e) { error("Port phải là số hợp lệ."); return; }
        String h = host.getText().trim();
        String u = username.getText().trim();
        char[] pw = password.getPassword();
        runTask("Đang kết nối đến FTP Server…", () -> {
            FtpSession ftp = null;
            try {
                ftp = FtpSession.connect(h, portNumber, u, pw);
                return new ConnectionSnapshot(ftp, snapshot(ftp));
            } catch (IOException | RuntimeException ex) {
                if (ftp != null) ftp.close();
                throw ex;
            } finally { java.util.Arrays.fill(pw, '\0'); }
        }, result -> {
            session = result.ftp();
            password.setText("");
            showDirectory(result.directory());
        });
    }

    private void refreshAsync() {
        if (session == null) return;
        runTask("Đang lấy danh sách file…", () -> snapshot(session), this::showDirectory);
    }

    private void changeDirectory(String dir) {
        if (session == null) return;
        runTask("Đang mở thư mục…", () -> {
            session.changeDirectory(dir);
            return snapshot(session);
        }, this::showDirectory);
    }

    private void uploadSelected() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn tệp để Upload lên Server");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path local = chooser.getSelectedFile().toPath();
        String name = local.getFileName().toString();
        int confirmation = JOptionPane.showConfirmDialog(this,
                "Upload '" + name + "' vào thư mục hiện tại?\nTệp trùng tên trên server có thể bị ghi đè.",
                "Xác nhận Upload", JOptionPane.YES_NO_OPTION);
        if (confirmation != JOptionPane.YES_OPTION) return;
        runTask("Đang Upload " + name + "…", () -> {
            session.upload(local, name, bytes -> updateBytes("Upload", bytes));
            return snapshot(session);
        }, this::showDirectory);
    }

    private void downloadSelected() {
        FtpEntry item = selected();
        if (item == null || item.directory()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn nơi lưu tệp tải về");
        chooser.setSelectedFile(new java.io.File(item.name()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Tệp đã tồn tại. Bạn có muốn ghi đè không?", "Xác nhận Download", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        runTask("Đang Download " + item.name() + "…", () -> {
            session.download(item.name(), target, bytes -> updateBytes("Download", bytes));
            return target;
        }, result -> status.setText("Đã tải thành công: " + result));
    }

    private void createFolder() {
        String name = JOptionPane.showInputDialog(this, "Tên thư mục mới:", "Tạo thư mục", JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) return;
        runTask("Đang tạo thư mục…", () -> {
            session.makeDirectory(name.trim());
            return snapshot(session);
        }, this::showDirectory);
    }

    private void renameSelected() {
        FtpEntry item = selected();
        if (item == null) return;
        String newName = JOptionPane.showInputDialog(this, "Tên mới:", item.name());
        if (newName == null || newName.isBlank() || newName.equals(item.name())) return;
        runTask("Đang đổi tên…", () -> {
            session.rename(item.name(), newName.trim());
            return snapshot(session);
        }, this::showDirectory);
    }

    private void deleteSelected() {
        FtpEntry item = selected();
        if (item == null) return;
        String question = item.directory() ? "Xóa thư mục RỖNG '" + item.name() + "'?" : "Xóa tệp '" + item.name() + "' trên server?";
        if (JOptionPane.showConfirmDialog(this, question, "Xác nhận xóa", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        runTask("Đang xóa…", () -> {
            if (item.directory()) session.removeDirectory(item.name());
            else session.deleteFile(item.name());
            return snapshot(session);
        }, this::showDirectory);
    }

    private void updateBytes(String action, long count) {
        // Swing UI updates must be performed on the EDT, not in the socket worker.
        SwingUtilities.invokeLater(() -> status.setText(action + ": " + count + " bytes đã truyền…"));
    }

    private <T> void runTask(String message, Callable<T> task, Consumer<T> onSuccess) {
        if (busy) return;
        busy = true;
        status.setText(message);
        progress.setIndeterminate(true);
        updateEnabled();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                progress.setIndeterminate(false);
                try { onSuccess.accept(get()); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    error("Thao tác bị gián đoạn.");
                } catch (ExecutionException ex) {
                    Throwable reason = ex.getCause() == null ? ex : ex.getCause();
                    error(reason.getMessage() == null ? reason.toString() : reason.getMessage());
                } catch (RuntimeException ex) {
                    error(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                }
                updateEnabled();
            }
        }.execute();
    }

    private void error(String message) {
        status.setText("Lỗi: " + message);
        JOptionPane.showMessageDialog(this, message, "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new FtpClientApp().setVisible(true));
    }
}
