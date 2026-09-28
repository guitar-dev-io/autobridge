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

# ระบุโหมด input (touch / rotary / touchpad)
./desktop-head-unit --input-mode touch
```

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
- ปิด DHU ด้วย `Ctrl + C` ที่หน้าต่าง terminal




adb -s YXEMRCGYAI49S4SS forward tcp:5277 tcp:5277
adb  forward tcp:5277 tcp:5277
cd ~/Library/Android/sdk/extras/google/auto/
./desktop-head-unit


adb forward --remove-all
adb forward tcp:5277 tcp:5277
adb forward --list