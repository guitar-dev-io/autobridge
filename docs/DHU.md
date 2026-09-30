# การเปิด DHU (Desktop Head Unit)

DHU (Desktop Head Unit) คือตัวจำลอง Android Auto บนเครื่อง desktop ของ Google
ใช้สำหรับทดสอบแอป Android Auto โดยไม่ต้องต่อกับหน่วยแสดงผลในรถจริง

## 1. เตรียมก่อนใช้งาน (ทำครั้งเดียว)

### ติดตั้ง DHU
เปิด **Android Studio → SDK Manager → SDK Tools** แล้วติดตั้ง
**Android Auto Desktop Head Unit Emulator**

Path ของ DHU บน macOS โดยทั่วไปคือ:

```
~/Library/Android/sdk/extras/google/auto/desktop-head-unit
```

### เปิด head unit server บนมือถือ
1. เปิดแอป **Android Auto** บนมือถือ
2. เข้า **ตั้งค่า** → แตะ **เวอร์ชัน** หลาย ๆ ครั้งจนปลดล็อก Developer mode
3. เปิดเมนู (จุดสามจุดมุมขวาบน) → **Start head unit server**

## 2. คำสั่งเปิด DHU

```bash
# เชื่อมต่อ ADB กับมือถือ (ต่อ USB) แล้ว forward port
adb forward tcp:5277 tcp:5277

# รัน DHU
cd ~/Library/Android/sdk/extras/google/auto/
./desktop-head-unit
```

## 3. ตัวเลือกที่ใช้บ่อย

```bash
# ใช้ไฟล์ config กำหนดความละเอียด/ขนาดหน้าจอ
./desktop-head-unit -c ~/.android/headunit.ini

# ระบุโหมด input (touch / rotary / hybrid)
./desktop-head-unit -i touch
```

DHU 2.0 ใช้ `-i` / `--input` ไม่ใช่ `--input-mode` (ถ้าใส่ผิดมันจะพิมพ์ usage แล้วออกทันที)
และค่าที่รับมีแค่ `touch|rotary|hybrid`

## 4. เชื่อมต่อผ่าน Wi-Fi (ไม่ใช้ USB)

```bash
# ต่อ USB ครั้งแรกเพื่อเปิด TCP/IP mode
adb tcpip 5555
adb connect <IP-มือถือ>:5555

# forward port แล้วรัน DHU ตามปกติ
adb forward tcp:5277 tcp:5277
./desktop-head-unit
```

## หมายเหตุ

- ถ้า `adb forward` แล้ว DHU ยังต่อไม่ติด ให้ตรวจว่าเปิด **Start head unit server**
  บนมือถือแล้ว และมือถือปลดล็อกหน้าจออยู่
- พอร์ตมาตรฐานที่ DHU ใช้คือ `5277`
- head unit server รับการเชื่อมต่อได้ครั้งเดียว ถ้า DHU หลุดหรือปิดไป ต้อง **หยุด** แล้ว
  **เริ่ม** server ใหม่จากเมนูสามจุดก่อนต่อรอบถัดไป ไม่งั้น DHU จะบอกว่า `connected`
  แต่ไม่มีภาพ และ `screenshot` จะตอบ `Don't have video focus`

## ข้อจำกัดที่เจอจริง: DHU 2.0 ใช้กับ Android Auto รุ่นใหม่ไม่ได้

ทดสอบเมื่อ 2026-09-30: Android Auto **17.7.663654** กับ DHU **2.0 (build 2022-03-30)**
บน macOS arm64 — TLS handshake ผ่าน (`SSL negotiation finished successfully`) แต่หลังจากนั้น
มือถือตัดการเชื่อมต่อทันที DHU ขึ้น `Failed to read from transport - disconnect` และใน logcat
ฝั่งมือถือขึ้น:

```
GH.ConnLoggerV2: Session ..., event 41, ..., USB_ISSUE_PROJECTION_NOT_STARTED
GH.ConnLoggerV2: Session ..., event 42, ..., USB_MONITOR_STOPPED
```

ไม่มี virtual display ถูกสร้าง แปลว่า projection ไม่เริ่มเลย

`sdkmanager --list` มี `extras;google;auto` แค่เวอร์ชัน **2.0** เท่านั้น ไม่มีตัวใหม่กว่าให้อัปเดต
ดังนั้นบนเครื่องที่ Android Auto เป็นรุ่นใหม่ **ต้องทดสอบกับ head unit จริง** หรือย้อนเวอร์ชัน
Android Auto ลง (ซึ่งทำให้สภาพแวดล้อมต่างจากที่ผู้ใช้เจอจริง จึงไม่แนะนำสำหรับไล่บั๊ก)
- ปิด DHU ด้วย `Ctrl + C` ที่หน้าต่าง terminal




adb -s YXEMRCGYAI49S4SS forward tcp:5277 tcp:5277
adb  forward tcp:5277 tcp:5277
cd ~/Library/Android/sdk/extras/google/auto/
./desktop-head-unit


adb forward --remove-all
adb forward tcp:5277 tcp:5277
adb forward --list