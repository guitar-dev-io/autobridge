# การ Sideload AutoBridge ให้โชว์บน Android Auto

อัปเดต: 2026-10-02

เอกสารนี้อธิบายวิธีติดตั้ง AutoBridge แบบ sideload ให้ปรากฏบนจอ Android Auto ของรถ
ครอบคลุมทั้งทาง **root** และทาง **Shizuku** (ไม่ต้อง root) อ้างอิงจากโค้ดจริงในโปรเจกต์
(`InstallerSpoofController`, `InstallerSource`, `ProjectionSetupActivity`)

> โหมดนี้ใช้ **เส้นทาง projection** (CATEGORY_PROJECTION) ซึ่งทำให้ AutoBridge เปิดในพื้นที่หลัก
> และแอปนำทาง (Google Maps) หดไปอยู่แผงข้าง เส้นทางนี้ใช้ SDK ไม่เป็นทางการ (`aauto.aar`)
> Google Play ไม่รับ จึงมีเฉพาะ flavor `personal` และ `lab` เท่านั้น (ดู `app/build.gradle.kts`)

---

## ทำไมแค่ลง APK เฉย ๆ ไม่พอ

Android Auto จะโชว์เฉพาะแอปที่ระบบบันทึกว่า **ติดตั้งมาจาก Play Store** (`com.android.vending`)
ตัวที่ตัดสินคือค่า "install source" ที่ package manager เก็บไว้ ถ้า sideload มา ค่านี้จะไม่ใช่
Play Store → Android Auto ซ่อนแอป (ดูเหตุผลใน `InstallerSource.kt`)

**หมายเหตุสำคัญ:** `pm set-installer` (เปลี่ยน install source หลังติดตั้ง) ใช้ไม่ได้ เพราะระบบบังคับ
ว่าผู้เรียกต้องแชร์ใบเซ็นกับ `com.android.vending` ซึ่ง shell/root ทำไม่ได้ สิ่งที่ได้ผลคือตั้ง
install source **ตอนติดตั้ง** ด้วย `pm install -i` ซึ่งไม่มีการเช็กใบเซ็น ดังนั้นวิธีแก้คือ
**reinstall APK ตัวเดิมในที่เดิม** โดยระบุ Play Store เป็น installer — ไม่ได้แก้หรือเซ็น APK ใหม่
(เทคนิคเดียวกับที่ KingInstaller และตัวติดตั้งของ Fermata ใช้)

การติดตั้งจึงมี **3 ส่วน**

---

## ส่วนที่ 1 — ลง APK (flavor projection)

ต้องเป็น flavor `personal` หรือ `lab` เท่านั้น (`safe` ไม่มี source set projection)

```bash
# ตัวอย่าง build + ติดตั้ง flavor personal (debug)
./gradlew :app:installPersonalDebug

# หรือ build APK แล้วลงเอง
./gradlew :app:assemblePersonalDebug
adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk
```

ลงด้วยวิธีไหนก็ได้ที่ sideload ได้ (adb, ตัวจัดการไฟล์ APK ฯลฯ)

---

## ส่วนที่ 2 — เขียน install source ให้เป็น Play Store

หน้า **"AutoBridge on Android Auto"** (`ProjectionSetupActivity`) มีปุ่ม **"Enable on Android Auto"**
ที่สั่ง `InstallerSpoofController.makeVisible()` ให้ โดยลองสิทธิ์ยกระดับตามลำดับ:

1. **Root** — ถ้ามี root จะรันผ่าน `su -c` ตรง ๆ ไม่ต้องขออะไร
2. **Shizuku** — ถ้าไม่มี root ใช้ Shizuku รันคำสั่งใน shell-UID แทน

คำสั่งที่รันจริง (จาก `InstallerSource.installWithPlayStoreSourceCommand`):

```
pm install -i com.android.vending -r <path ไป base.apk ของตัวเอง>
```

- `-i com.android.vending` = ตั้ง installer เป็น Play Store
- `-r` = reinstall ทับของเดิม เก็บข้อมูล/แอปไว้
- จากนั้นระบบ **อ่านค่า install source กลับมายืนยัน** (`pm list packages -i`) ว่าสำเร็จจริง
  ไม่เชื่อแค่ exit code

### ทาง A — มี root (เช่น Magisk)

> ใช้ root ยี่ห้อไหนก็ได้ที่ให้คำสั่ง `su` (ตรวจด้วย `su -c id` ว่าได้ `uid=0`)
> โค้ดไม่ได้ผูกกับ KingRoot/แอปใดเป็นพิเศษ

1. เปิดแอป AutoBridge → ไปหน้า **AutoBridge on Android Auto**
2. ให้สิทธิ์ root เมื่อ `su` ขอ (ป๊อปอัพของตัวจัดการ root)
3. กด **"Enable on Android Auto"**
4. รอข้อความ "Done via ROOT. Reconnect Android Auto…"

### ทาง B — ไม่มี root ใช้ Shizuku

Shizuku เปิดได้ด้วย **wireless debugging** โดยไม่ต้อง root

1. ติดตั้งแอป Shizuku แล้วเริ่ม service ตามคู่มือ Shizuku
   (Android 11+ ใช้ wireless debugging; รุ่นเก่าใช้ adb)
2. เปิด AutoBridge → หน้า **AutoBridge on Android Auto**
   - ถ้าเห็นสถานะ "Shizuku: needs permission" ให้กดปุ่ม **"Grant Shizuku permission"** แล้วอนุญาต
   - ถ้า "Shizuku: not running" ให้กลับไปเริ่ม Shizuku ก่อน
3. เมื่อ "Shizuku: ready" กด **"Enable on Android Auto"**
4. รอข้อความ "Done via SHIZUKU. Reconnect Android Auto…"

> ถ้าปุ่มขึ้นว่าทำไม่ได้: ตรวจว่า Shizuku กำลังรัน และให้ permission แล้ว หรือมี root ที่ให้ `su`
> ข้อความ error ที่เป็นไปได้ (จากโค้ด): "No root or Shizuku permission available",
> "reinstall … did not run", "installer still …" (เขียนทับไม่สำเร็จ ให้ลองใหม่)

---

## ส่วนที่ 3 — เปิด "Unknown sources" ใน Android Auto (ทำมือ)

ขั้นนี้แอปทำแทนไม่ได้ (สวิตช์อยู่ในแอป Android Auto เอง) จาก `ProjectionSetupActivity` (MANUAL_STEPS):

1. เปิดตั้งค่า **Android Auto** บนมือถือ → แตะบรรทัด **Version** 10 ครั้ง เพื่อปลด Developer settings
2. เมนูสามจุด → **Developer settings** → เปิด **"Unknown sources"**
3. **ต่อรถใหม่** แล้ว **"AutoBridge Browser"** จะปรากฏในรายการแอปของ Android Auto

---

## สรุปขั้นตอน

| ขั้น | ทำผ่านอะไร | แอปทำให้อัตโนมัติไหม |
|---|---|---|
| 1. ลง APK | adb / ตัวติดตั้ง APK (flavor `personal` หรือ `lab`) | ไม่ (ลงเอง) |
| 2. เขียน install source = Play Store | root (`su`) **หรือ** Shizuku — ปุ่มในแอป | ได้ ถ้ามี root/Shizuku |
| 3. เปิด "Unknown sources" | ตั้งค่า Android Auto บนมือถือ | ไม่ได้ (ทำมือ) |

เส้นทางง่ายสุดสำหรับคนไม่ root:
**เปิด Shizuku (wireless debugging) → กด Enable ในแอป → เปิด Unknown sources ใน Android Auto → ต่อรถใหม่**

---

## ตรวจสอบ / แก้ปัญหา

- เช็ก install source ปัจจุบัน:
  ```bash
  adb shell pm list packages -i dev.autobridge
  # ต้องเห็น installer=com.android.vending ถึงจะโชว์บน Android Auto
  ```
- ถ้ากด Enable แล้วแอปยังไม่โผล่: ยืนยันว่าเปิด "Unknown sources" และ **ต่อรถใหม่** แล้ว
- หน้า setup แสดงสถานะ 3 บรรทัด: visible/hidden, recorded installer, และสถานะ Shizuku
  ใช้ไล่ปัญหาได้ว่าค้างขั้นไหน
- ทดสอบด้วย DHU: ดู `docs/DHU.md` (DHU 2.0 ต่อ Android Auto รุ่นใหม่ไม่ติด ต้องใช้ head unit จริง)

---

## หมายเหตุด้านความถูกต้อง/กฎ

- เส้นทาง projection + การเขียน install source เป็น Play Store เป็นวิธี **เพื่อการใช้งานส่วนตัว/
  ทดสอบ** เท่านั้น Google Play ไม่รับแอปแนวนี้ (ดู ROADMAP → Distribution)
- ไม่มีการแก้หรือเซ็น APK ใหม่ เปลี่ยนแค่ค่า "ใครติดตั้ง" ของแอปที่ผู้ใช้ติดตั้งเอง
- flavor `safe` (สำหรับ Play) ไม่มีโค้ดส่วนนี้คอมไพล์เข้าไปเลย
