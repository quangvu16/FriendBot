# Hướng dẫn điều khiển FriendBot bằng giọng nói (Android & iOS)

Hướng dẫn này dành cho trang **Voice Control** (Điều khiển giọng nói) trong ứng dụng FriendBot —
nói với robot bằng tiếng Việt, tiếng Anh hoặc tiếng Trung, bấm các nút trên màn hình, hoặc để trợ
lý Xiaozhi hiểu lời bạn. Tài liệu này liệt kê **toàn bộ lệnh điều khiển**, kể cả các lệnh mới:
đèn LED, di chuyển lâu hơn, đọc khoảng cách cảm biến siêu âm, hát và quay, đi hình tam giác, đi
hình tròn, quay 360 độ và quay trái phải.

---

## 1. Mở trang Voice Control

1. Mở ứng dụng FriendBot, ở màn hình **Home** chọn mục **AI** → **Voice Control**.
2. Kết nối robot: mở màn hình Bluetooth/USB và chọn robot **OpenBot: MTV** (BLE) hoặc cắm cáp USB.
   Dòng trạng thái trên trang Voice Control báo **Connected** là xong.
3. Chưa kết nối robot vẫn nói chuyện được, nhưng robot không di chuyển.

Trò chuyện với robot có ba đường, theo thứ tự ưu tiên:

1. **Trợ lý Xiaozhi** (nếu đã cấu hình máy chủ) — hiểu lời tự nhiên, trả lời và chạy lệnh.
2. **Bộ từ khóa có sẵn** — không cần mạng, khớp từ khóa trực tiếp (bảng ở mục 4).
3. **Ô nhập chữ** — gõ lệnh rồi bấm Send, dùng khi không muốn nói.

---

## 2. Các nút bấm nhanh

Dưới ô hội thoại là ba hàng nút bấm (không cần nói, bấm là chạy):

| Hàng | Nút | Tác dụng |
|---|---|---|
| 1 | **Forward / Backward / Turn left / Turn right** | Di chuyển: tiến, lùi, quay trái, quay phải |
| 1 | **Stop** | Dừng ngay lập tức |
| 2 | **Lights** | Bật / tắt đèn LED trước–sau (bấm lần nữa để đảo trạng thái) |
| 2 | **Sonar** | Đọc khoảng cách cảm biến siêu âm (hiển thị: *Distance: … cm*) |
| 2 | **Sing** | Hát và quay (vừa "hát" vừa xoay một vòng) |
| 2 | **Spin 360** | Quay tại chỗ đúng 360 độ |
| 3 | **Triangle** | Đi theo hình tam giác đều (3 cạnh, 3 góc rẽ) |
| 3 | **Circle** | Đi theo một vòng tròn |
| 3 | **Left+Right** | Quay trái rồi quay phải (nhắc tới nhau, rồi dừng) |
| 3 | **Turn 180** | Quay người lại 180 độ |

Thanh **Speed** bên dưới chỉnh tốc độ cho mọi lệnh di chuyển.

---

## 3. Các lệnh mới — nói thế nào?

### 3.1 Điều khiển đèn LED và xi nhan

| Nói (VI) | Nói (EN) | Robot làm |
|---|---|---|
| bật đèn, mở đèn | lights on, turn on the light | Bật đèn trước và sau |
| tắt đèn | lights off, turn off the light | Tắt hết đèn |
| xi nhan trái | signal left, left indicator | Bật xi nhan trái (nhấp nháy) |
| xi nhan phải | signal right, right indicator | Bật xi nhan phải (nhấp nháy) |
| đèn hazard, xi nhan cả hai | hazard, hazard lights | Bật cả hai xi nhan |
| tắt xi nhan | signals off, indicators off | Tắt hết xi nhan |

### 3.2 Di chuyển lâu hơn

Thêm một con số vào lệnh di chuyển — robot chạy đúng bằng đó lâu rồi tự dừng:

| Nói | Nghĩa |
|---|---|
| tiến 5 giây | đi thẳng 5 giây |
| go forward for 10 seconds | đi thẳng 10 giây |
| lùi 3 giây / back 3000 ms | lùi lại trong 3 giây / 3000 ms |
| tiến lâu / go for a while | đi thẳng 5 giây |
| tiến 2 phút | đi thẳng 2 phút (tối đa 15 giây mỗi lệnh) |

Mỗi lệnh di chuyển tự dừng sau tối đa **15 giây**; nói **stop / dừng lại** để dừng ngay bất cứ lúc
nào.

### 3.3 Đọc khoảng cách cảm biến siêu âm

| Nói (VI) | Nói (EN) | Robot làm |
|---|---|---|
| khoảng cách, khoảng cách bao nhiêu | sonar, distance, how far | Đọc cảm biến siêu âm và trả lời *Distance: … cm* |

Robot trả lời khoảng cách vật cản phía trước (đơn vị cm) ngay trong ô hội thoại.

### 3.4 Các trò (tricks)

| Nói (VI) | Nói (EN) | Robot làm |
|---|---|---|
| hát, hát và quay | sing, song | Hát "♪ La la la…" và xoay một vòng 360° |
| tam giác | triangle | Đi theo hình tam giác đều |
| hình tròn, vòng tròn | circle | Đi theo một vòng tròn |
| 360, xoay, một vòng | spin, 360 | Quay tại chỗ 360 độ |
| quay lại, quay đầu | turn around | Quay người 180 độ |
| trái phải, phải trái | left and right | Quay trái–phải–trái–phải rồi dừng |

Các trò dùng tốc độ của thanh **Speed**; chỉnh chậm lại nếu sàn hẹp.

---

## 4. Bảng từ khóa đầy đủ (nói / gõ / ngoại tuyến)

| Tiếng Việt | English | 中文 | Tác dụng |
|---|---|---|---|
| tiến, đi | forward, go, ahead, straight | 前进, 向前, 走 | Đi thẳng |
| lùi, lùi lại | back, reverse, go back | 后退, 倒, 向后 | Đi lùi |
| trái, rẽ trái | left, turn left | 左, 左转 | Quay trái |
| phải, rẽ phải | right, turn right | 右, 右转 | Quay phải |
| dừng, dừng lại | stop, halt | 停, 停止 | Dừng (ưu tiên cao nhất) |
| nhanh, tăng tốc | faster, speed up | 快, 加速 | Tốc độ +15 % |
| chậm, giảm tốc | slower, slow down | 慢, 减速 | Tốc độ −15 % |
| bật đèn, mở đèn | lights on | 开灯 | Bật đèn LED |
| tắt đèn | lights off | 关灯 | Tắt đèn LED |
| xi nhan trái | signal left | 左转向灯 | Bật xi nhan trái |
| xi nhan phải | signal right | 右转向灯 | Bật xi nhan phải |
| đèn hazard | hazard | 危险灯 | Bật cả hai xi nhan |
| tắt xi nhan | signals off | 关转向灯 | Tắt xi nhan |
| khoảng cách, cảm biến | sonar, distance | 距离, 超声波 | Đọc siêu âm |
| hát, hát và quay | sing | 唱歌 | Hát và quay |
| tam giác | triangle | 三角形 | Đi hình tam giác |
| hình tròn, vòng tròn | circle | 圆形, 圆圈 | Đi hình tròn |
| 360, xoay, một vòng | spin, 360 | 旋转, 转一圈 | Quay 360 độ |
| quay lại, quay đầu | turn around | 掉头, 转身 | Quay 180 độ |
| trái phải, phải trái | left and right | 左右 | Quay trái–phải |

Thứ tự ghép từ quan trọng: **stop** thắng mọi lệnh ("dừng lại" phải dừng, không đi tiếp), và cụm
"trái phải" được nhận ra trước lệnh "trái"/"phải" riêng lẻ.

Nếu đã cấu hình trợ lý Xiaozhi, lời tự nhiên cũng được hiểu: "đi chậm về phía trước ba giây",
"drive backwards slowly for three seconds", "quay phải rồi hát"… — mô hình chọn hành động, tốc
độ và thời gian phù hợp.

---

## 5. Trợ lý Xiaozhi và lệnh MCP

Với máy chủ Xiaozhi đã cấu hình (mục 9 của
[hướng dẫn tiếng Anh](../README.md#9-configuring-xiaozhi)), robot còn có
thể gọi các công cụ MCP của chính nó:

| Công cụ | Tham số | Tác dụng |
|---|---|---|
| `self.robot.move` | direction: forward/backward/left/right, speed, duration_ms (tối đa 15000) | Di chuyển |
| `self.robot.stop` | — | Dừng ngay |
| `self.robot.led` | state: on / off | Bật / tắt đèn LED |
| `self.robot.indicator` | state: left / right / both / off | Bật / tắt xi nhan |
| `self.robot.sonar` | — | Đọc khoảng cách siêu âm |
| `self.robot.trick` | name: sing / triangle / circle / spin / turn_around / wiggle | Chạy một trò |
| `self.camera.take_photo` | question | Chụp ảnh và mô tả |

---

## 6. An toàn

* Mỗi lệnh di chuyển **tự dừng** sau thời gian đã hẹn (mặc định 1,2 giây, tối đa 15 giây).
* Nói **stop / dừng lại / 停** là thắng mọi lệnh khác.
* Firmware tự dừng động cơ khi mất Bluetooth, hoặc khi mất tín hiệu USB quá 0,75 giây; cảm biến siêu âm tự giảm tốc ở 50 cm và dừng ở 15 cm.
* Nút **STOP** đỏ trên trang điều khiển xe (dashboard) khóa mọi lệnh cho đến khi mở lại.
* Không hiểu lệnh → robot dừng và báo *Sorry, I did not understand that*.

---

## 7. Mẹo nhanh

* **Robot không chạy mà chỉ kêu**: kiểm tra pin động cơ, hoặc tăng `MOTOR_MIN_DUTY` trong firmware.
* **Robot đi ngược hướng**: xem lại `MOTOR_L_REVERSED` / `MOTOR_R_REVERSED` trong firmware.
* **Không nghe trả lời**: thoại đi qua Xiaozhi — điện thoại cần Wi-Fi hoặc dữ liệu di động.
* **Siêu âm không trả kết quả**: cảm biến chỉ đọc được vật cản thẳng phía trước trong khoảng vài cm
  đến ~2–3 m; thử lại gần tường.
* **Muốn dừng giữa chừng**: nói "stop" hoặc bấm nút **Stop** — cả khi robot đang chạy một trò.

Chúc bạn và robot vui vẻ! 🤖
