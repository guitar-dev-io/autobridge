# รัน AutoBridge iOS บนเครื่องจริง

ใช้กับ iPhone 8 Plus (iOS 16.7) ของทีม แต่ใช้กับเครื่องอื่นได้ถ้าเปลี่ยน UDID

ตอนนี้ Xcode 27 มองไม่เห็นเครื่อง iOS 16 นี้ (CoreDevice tunnel ไม่ขึ้น) จึงกด Run หรือใช้ debugger ของ Xcode
ไม่ได้ ให้ build ด้วย `xcodebuild` แล้วลงเครื่องและดู log ผ่าน libimobiledevice แทน

## ค่าที่ใช้

```bash
UDID=858074eff36623690c72df9e7625b0bde9d6411b   # ดูของเครื่องอื่นด้วย idevice_id -l
APP=~/Library/Developer/Xcode/DerivedData/AutoBridge-bhqconqtixaqwggtvlvnkcwknkmn/Build/Products/Debug-iphoneos/AutoBridge.app
```

ถ้าโฟลเดอร์ DerivedData ไม่ตรง หาได้จาก
`grep -m1 "Debug-iphoneos/AutoBridge.app" /tmp/devbuild.log`

## เตรียมครั้งแรก

```bash
brew install xcodegen libimobiledevice ideviceinstaller
```

บน iPhone:
1. เสียบสายเข้า Mac ตรง ๆ แล้วปลดล็อกจอ
2. กด **Trust** เมื่อ popup "Trust This Computer?" ขึ้น
3. เปิด Settings → Privacy & Security → Developer Mode แล้วรีสตาร์ตเครื่อง
4. หลังลงแอปครั้งแรก ถ้าขึ้น "Untrusted Developer" ให้ไปที่ Settings → General → VPN & Device
   Management แล้ว Trust ใบรับรอง Apple Development

เช็คว่า Mac เห็นเครื่อง:

```bash
idevice_id -l                       # ต้องเห็น UDID
idevicepair validate                # ต้องขึ้น SUCCESS
ideviceinfo -k ProductVersion       # เวอร์ชัน iOS
```

## 1. Build (Debug)

```bash
cd ~/Documents/ChatGPT/AutoBridge/ios
xcodegen generate
xcodebuild -project AutoBridge.xcodeproj -scheme AutoBridge \
  -destination 'generic/platform=iOS' -configuration Debug \
  -allowProvisioningUpdates build > /tmp/devbuild.log 2>&1
grep -E "error:|BUILD" /tmp/devbuild.log
```

ต้องขึ้น `** BUILD SUCCEEDED **` ถ้าไม่ผ่าน ดู error ใน `/tmp/devbuild.log`

- ส่ง output ลงไฟล์เสมอ เพราะ output ของ xcodebuild ยาวมาก
- `-allowProvisioningUpdates` ให้ Xcode ดึง profile ที่มีสิทธิ์ CarPlay มาเอง
- ถ้าขึ้นว่าโปรเจกต์ "is not a valid property list" แปลว่า Xcode ที่เปิดค้างกำลังโหลดโปรเจกต์อยู่ ให้รัน
  `xcodegen generate` ใหม่แล้ว build อีกรอบ

เช็คผลที่ได้ (ไม่บังคับ):

```bash
plutil -extract MinimumOSVersion raw "$APP/Info.plist"        # ต้องเป็น 16.0
codesign -d --entitlements - "$APP" 2>/dev/null | grep carplay # ต้องมี carplay-audio
```

## 2. ลงเครื่อง

```bash
ideviceinstaller -u $UDID install "$APP"
```

ต้องเห็น `Install: Complete` จากนั้นกดเปิดแอปบน iPhone เอง หรือเสียบ CarPlay ในรถ

ถ้าขึ้น `No device found` ให้ปลดล็อกจอแล้วเช็ค `idevice_id -l` อีกครั้ง

เช็คว่าลงแล้ว / ลบแอป:

```bash
ideviceinstaller -u $UDID list | grep -i autobridge
ideviceinstaller -u $UDID uninstall dev.autobridge
```

## 3. ดู log สด (แทน debug console)

```bash
idevicesyslog -u $UDID -p AutoBridge -K
```

กด `Ctrl+C` เพื่อหยุด

- `-p AutoBridge` แสดงเฉพาะ log จากโปรเซสของแอป ถ้าไม่ขึ้นอะไรเลยให้ใช้ `-m AutoBridge` แทน
- `-K` ตัด log ของ kernel ออก
- ข้อความจาก `print()` จะไม่ขึ้นที่นี่ ต้องเขียนด้วย `Logger` หรือ `os_log`

## 4. ดึง crash log

```bash
mkdir -p /tmp/ab-crash
idevicecrashreport -u $UDID -k -f AutoBridge /tmp/ab-crash
ls -t /tmp/ab-crash
```

- `-k` เก็บ crash log ไว้ในเครื่องด้วย ไม่ลบหลังดึง
- `-f AutoBridge` ดึงเฉพาะของแอปนี้ (ตัวพิมพ์เล็กใหญ่ต้องตรง)

## ทำครบในรอบเดียว

```bash
cd ~/Documents/ChatGPT/AutoBridge/ios && xcodegen generate && \
xcodebuild -project AutoBridge.xcodeproj -scheme AutoBridge \
  -destination 'generic/platform=iOS' -configuration Debug \
  -allowProvisioningUpdates build > /tmp/devbuild.log 2>&1 && \
ideviceinstaller -u $UDID install "$APP" && \
idevicesyslog -u $UDID -p AutoBridge -K
```

ถ้า build ไม่ผ่าน คำสั่งจะหยุดก่อนลงเครื่อง ให้ดู `/tmp/devbuild.log`
