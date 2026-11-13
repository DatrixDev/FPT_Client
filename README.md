# FTP Client - Đồ án Lập trình mạng (Java Swing)

Ứng dụng FTP Client tự viết bằng Java 17+ và TCP Socket, không dùng thư viện FTP có sẵn.

## Chức năng

- Kết nối FTP Server, đăng nhập bằng USER/PASS.
- Duyệt thư mục qua PWD, CWD, MLSD (hoặc LIST dự phòng).
- Upload / Download file nhị phân (STOR / RETR).
- Tạo thư mục (MKD), đổi tên (RNFR/RNTO), xóa file (DELE), xóa thư mục rỗng (RMD).
- Giao diện Java Swing và thanh trạng thái truyền dữ liệu.

## Yêu cầu

- JDK 17 trở lên (chạy `java -version` và `javac -version`).
- FTP Server để thử nghiệm, ví dụ FileZilla Server.

## Chạy trên Windows

Giải nén, mở thư mục dự án và nhấp đúp **run.bat**.
Hoặc mở Terminal tại thư mục dự án rồi chạy `run.bat`.

## Chạy Linux/macOS

Chạy `bash run.sh`.

## Thiết lập FileZilla Server để demo

1. Cài FileZilla Server và tạo người dùng FTP, ví dụ `demo` với mật khẩu riêng.
2. Chọn thư mục được chia sẻ và cấp quyền đọc/ghi phù hợp cho tài khoản demo.
3. Bật FTP listener (ví dụ port **21**, hoặc **2121** nếu không dùng được 21).
4. Cấu hình passive data port range và cho phép các cổng đó qua tường lửa nếu dùng máy khác.
5. Mở FTP Client, nhập `127.0.0.1`, port, user và password rồi bấm **Kết nối**.
6. Thử tạo thư mục, upload tệp .txt, download, đổi tên và xóa.

**Ghi chú:** Dự án này dùng **FTP thuần** (RFC 959), **không phải FTPS/SFTP**. FTP truyền cả mật khẩu và dữ liệu **không mã hóa** nên chỉ sử dụng trong môi trường thực hành/localhost hoặc mạng đáng tin cậy. Không nhập mật khẩu tài khoản quan trọng.

## Kiến thức từ slide môn Lập trình mạng

- Bài 3, slide 8: FTP Client / FTP Server và hệ thống file phía Client, Server.
- Bài 4, slide 3 & 5: FTP chạy trên TCP, cổng mặc định 21.
- Bài 4, slide 18: Client: socket → connect → send/recv → close.
- Bài 4, slide 22: Socket và input/output stream trong Java.

Mã triển khai các câu lệnh FTP theo RFC 959 và chế độ kết nối dữ liệu thụ động EPSV/PASV (để tương thích FileZilla). Server thử nghiệm **không** phải một thành phần cần tự code trong bài FTP Client.

## Cấu trúc

```text
FPT_Client/
├── src/main/java/com/datrixdev/ftpclient/
│   ├── FtpClientApp.java  # Giao diện Java Swing
│   ├── FtpSession.java    # Lệnh FTP + điều khiển TCP + dữ liệu TCP
│   ├── FtpEntry.java      # Thông tin file/thư mục
│   └── FtpReply.java      # Phản hồi FTP
├── run.bat                # Chạy trên Windows
├── run.sh                 # Chạy trên Linux/macOS
└── README.md
```

## Demo nên trình bày với giảng viên

- Giải thích mô hình Client/Server; đường TCP điều khiển cổng 21 khác đường TCP truyền dữ liệu.
- Khởi chạy FTP Server đã cấu hình, kết nối Client.
- Demo xem danh sách file, tạo thư mục, upload, download, đổi tên, xóa.
- Mở `FtpSession.java` giải thích USER, PASS, PWD, MLSD, STOR, RETR, QUIT.