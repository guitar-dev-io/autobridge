# ติดตั้ง AutoBridge ให้โชว์บนจอ Android Auto 🚗📱

อัปเดต: 2026-10-05 · ใช้กับ AutoBridge `0.4.18` (versionCode 32)

คู่มือนี้เขียนจากโค้ดจริงในโปรเจกต์ (`InstallerSource`, `InstallerSpoofController`,
`ProjectionSetupActivity`, `app/build.gradle.kts`) และปรับโครงมาจากคู่มือติดตั้งของ
[ScreenOnAuto](https://github.com/slzn/ScreenOnAuto-releases#installation) ซึ่งเป็นแหล่งอ้างอิง
พฤติกรรมของโปรเจกต์นี้ ส่วนขั้นตอน KingInstaller อ้างจาก
[KingInstaller](https://github.com/fcaronte/KingInstaller)

> **ต้องมีหัวเครื่องที่รองรับ Android Auto** ถ้ารถต่อมือถือได้แค่ Bluetooth (เช่น Honda BR-V เจน 1)
> ขั้นที่ 2–4 ด้านล่างไม่มีผลอะไร ลง APK แล้วใช้งานบนจอมือถือ เสียงออกลำโพงรถทาง A2DP ได้เลย

---

## สารบัญ

0. [เครื่องมือที่ต้องโหลด — Shizuku / KingInstaller](#0-เครื่องมือที่ต้องโหลด--shizuku--kinginstaller)
1. [เลือก APK ให้ถูกตัว](#1-เลือก-apk-ให้ถูกตัว)
2. [ทำไมลง APK เฉย ๆ แล้วไม่โชว์บนจอรถ](#2-ทำไมลง-apk-เฉย-ๆ-แล้วไม่โชว์บนจอรถ)
3. [เส้นทาง A — KingInstaller (ง่ายสุด ไม่ต้องใช้คอม)](#3-เส้นทาง-a--kinginstaller-ง่ายสุด-ไม่ต้องใช้คอม)
4. [เส้นทาง B — ปุ่มในแอป (root หรือ Shizuku)](#4-เส้นทาง-b--ปุ่มในแอป-root-หรือ-shizuku)
5. [ขั้นบังคับที่แอปทำแทนไม่ได้: Unknown sources](#5-ขั้นบังคับที่แอปทำแทนไม่ได้-unknown-sources)
6. [ตรวจสอบว่าสำเร็จ](#6-ตรวจสอบว่าสำเร็จ)
7. [Play Protect, MIUI และ Restricted settings](#7-play-protect-miui-และ-restricted-settings)
8. [แก้ปัญหา](#8-แก้ปัญหา)
9. [ข้อควรรู้เรื่องนโยบาย](#9-ข้อควรรู้เรื่องนโยบาย)

---

## 0. เครื่องมือที่ต้องโหลด — Shizuku / KingInstaller

สองตัวนี้เป็นแอปของผู้พัฒนาอื่น **ไม่ได้มาพร้อม AutoBridge** และไม่มีความเกี่ยวข้องกัน โหลดจากต้นทางเท่านั้น
ถ้าเจอไฟล์ที่อ้างว่าเป็นสองตัวนี้จากที่อื่น ให้ถือว่าไม่น่าเชื่อถือ

| เครื่องมือ | แหล่งดาวน์โหลด | ใช้ทำอะไรในคู่มือนี้ |
|---|---|---|
| **Shizuku** (RikkaApps)<br>`moe.shizuku.privileged.api` | [ซอร์สโค้ด](https://github.com/RikkaApps/Shizuku) · [Releases — APK](https://github.com/RikkaApps/Shizuku/releases/latest) · [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) · [IzzyOnDroid (F-Droid repo)](https://apt.izzysoft.de/fdroid/index/apk/moe.shizuku.privileged.api) · [เว็บโปรเจกต์](https://shizuku.rikka.app/) | ให้สิทธิ์ระดับ ADB กับแอปที่ขอ เริ่มผ่าน **wireless debugging** ของ Android 11+ ไม่ต้องใช้คอม ไม่ต้องรูท — ใช้ทั้งใน **Shizuku Trick** ของ KingInstaller (ข้อ 3) และปุ่มในแอป (ข้อ 4) |
| **KingInstaller** (fcaronte) | [ซอร์สโค้ด](https://github.com/fcaronte/KingInstaller) · [Releases — APK](https://github.com/fcaronte/KingInstaller/releases/latest) | ติดตั้ง APK โดยบันทึกค่า installer เป็น Play Store ซึ่งเป็นเงื่อนไขที่ทำให้ Android Auto ยอมแสดงแอปที่ sideload มา (ข้อ 3) มีเฉพาะไฟล์ APK **ไม่มีบน Google Play** |

เวอร์ชันที่ตรวจเมื่อ 2026-10-05: Shizuku `v13.6.0`, KingInstaller `v2.3` (ไฟล์ `KingInstaller-v2.3.apk`)
ลิงก์ข้างบนชี้ที่ release ล่าสุดของแต่ละโปรเจกต์ จึงไม่ต้องแก้คู่มือเมื่อทั้งสองออกเวอร์ชันใหม่

> ทั้งสองตัวต้องเปิด "ติดตั้งแอปที่ไม่รู้จัก" ให้เบราว์เซอร์หรือตัวจัดการไฟล์ก่อน และบนเครื่อง Xiaomi/POCO/Redmi
> (MIUI, HyperOS) Shizuku Trick ใช้ไม่ได้ ต้องใช้ **Root Trick** ของ KingInstaller — ดูข้อ 3

---

## 1. เลือก APK ให้ถูกตัว

เส้นทาง projection (ที่ทำให้ AutoBridge เปิดเต็มพื้นที่หลักบนจอรถ) คอมไพล์เข้าเฉพาะ flavor
`personal` และ `lab` เท่านั้น — `safe` ไม่มี source set นี้เลย (`app/build.gradle.kts:239`)
**ลง `safe` แล้วจะไม่มีหน้า "AutoBridge on Android Auto" ให้กด**

```bash
# แนะนำ: release ที่เซ็นด้วย keystore คงที่ของโปรเจกต์ (keystore/release.properties)
./gradlew :app:assemblePersonalRelease
# ไฟล์: app/build/outputs/apk/personal/release/app-personal-release.apk

# หรือ debug ถ้าจะไล่ log
./gradlew :app:assemblePersonalDebug
```

ใช้ release เมื่อจะลงไว้ใช้จริง เพราะ debug เซ็นด้วย `~/.android/debug.keystore` ซึ่งคีย์ต่างกัน
ทุกเครื่องที่ build → ลงทับของเดิมไม่ได้ ต้องถอนก่อน และ Play Protect เตือนแรงกว่า

---

## 2. ทำไมลง APK เฉย ๆ แล้วไม่โชว์บนจอรถ

Android Auto จะลิสต์เฉพาะแอปที่ package manager บันทึกว่า **ติดตั้งมาจาก Play Store**
(`com.android.vending`) ลง APK ตรง ๆ ค่านี้จะเป็นเบราว์เซอร์/ตัวจัดการไฟล์/`null` → Android Auto ซ่อนแอป

และ **แก้ย้อนหลังด้วย `pm set-installer` ไม่ได้** เพราะระบบบังคับว่าผู้เรียกต้องแชร์ใบเซ็นกับ
`com.android.vending` ซึ่ง shell หรือ root ทำไม่ได้ ที่ได้ผลคือตั้ง installer **ตอนติดตั้ง** ด้วย
`pm install -i` ซึ่งไม่มีการเช็กใบเซ็น (เหตุผลเต็มอยู่ใน `InstallerSource.kt`)

```
pm install -i com.android.vending -r <path ไป base.apk ของตัวเอง>
```

เป็นการ **reinstall APK ตัวเดิมในที่เดิม** (`-r` เก็บแอปและข้อมูลไว้) ไม่มีการแก้หรือเซ็น APK ใหม่
เปลี่ยนแค่ค่า "ใครติดตั้ง" ของแอปที่ผู้ใช้ลงเอง — หลักการเดียวกับที่ KingInstaller และตัวติดตั้งของ
Fermata ใช้

สรุปการติดตั้งจึงมี 3 ส่วน:

| ขั้น | ทำผ่านอะไร | แอปทำให้อัตโนมัติไหม |
|---|---|---|
| 1. ลง APK (flavor `personal`/`lab`) | KingInstaller / adb / ตัวจัดการไฟล์ | ไม่ |
| 2. เขียน install source = Play Store | KingInstaller **หรือ** ปุ่มในแอป (root/Shizuku) | ได้ ถ้ามี root หรือ Shizuku |
| 3. เปิด "Unknown sources" ใน Android Auto | ตั้งค่าแอป Android Auto | ไม่ได้ (ทำมือ) |

ถ้าลงด้วย KingInstaller ขั้น 1 กับ 2 จบในทีเดียว

---

## 3. เส้นทาง A — KingInstaller (ง่ายสุด ไม่ต้องใช้คอม)

ต่างจากคู่มือของ ScreenOnAuto ที่แยกทางตาม Android version (14+ ให้ไปลงผ่าน Play internal testing):
**AutoBridge ไม่มีทางนั้น** เพราะ flavor ที่ขึ้น Play ได้คือ `safe` ซึ่งไม่มีเส้นทาง projection อยู่ในตัว
ดังนั้นบน Android 14/15/16 ก็ยังใช้ KingInstaller หรือ root/Shizuku เหมือนกัน

1. โหลด `KingInstaller.apk` จาก [KingInstaller Releases](https://github.com/fcaronte/KingInstaller/releases/latest) (ดูแหล่งทั้งหมดที่ [ข้อ 0](#0-เครื่องมือที่ต้องโหลด--shizuku--kinginstaller))
   แล้วอนุญาตให้เบราว์เซอร์/ตัวจัดการไฟล์ติดตั้งแอปที่ไม่รู้จัก
2. เปิด KingInstaller → แตะไอคอนโฟลเดอร์ → เลือก `app-personal-release.apk`
3. ลองตามลำดับนี้ (KingInstaller ออกแบบมาให้ไล่ขึ้นทีละขั้น):
   - **Classic** — ไม่เปิดสวิตช์อะไร ใช้แฟล็กของ system installer เอง ผ่านบนเครื่อง stock ส่วนใหญ่
   - **Shizuku Trick** — เปิดสวิตช์นี้เมื่อ Classic ไม่ผ่าน (ต้องเริ่ม Shizuku ไว้ก่อน — ดูขั้นตอนในหัวข้อถัดไป)
   - **Root Trick** — เปิดเมื่อสองอันแรกไม่ผ่าน
4. กด Install แล้วยืนยันในหน้าติดตั้งของระบบ

> **Xiaomi / POCO / Redmi (MIUI, HyperOS):** ทาง Shizuku Trick ใช้ไม่ได้ ต้องใช้ **Root Trick**
> ตามที่ KingInstaller ระบุไว้เอง — ถ้าเครื่องคุณลงผ่าน KingInstaller ได้แล้ว นั่นคือทางนี้
> เสร็จแล้ว **ข้ามส่วนที่ 4 ได้เลย** เพราะ installer ถูกบันทึกเป็น Play Store ตั้งแต่ตอนลง

**ข้อควรระวังเวลาอัปเดตเวอร์ชันถัดไป:** ถ้าลงทับด้วยตัวจัดการไฟล์หรือ adb ธรรมดา ค่า installer
จะกลับไปเป็นตัวนั้น และแอปจะหายจากจอรถอีก — อัปเดตผ่าน KingInstaller หรือกดปุ่มในแอปซ้ำทุกครั้ง

---

## 4. เส้นทาง B — ปุ่มในแอป (root หรือ Shizuku)

ใช้เมื่อ APK ลงไปแล้วแต่ยังโชว์บนจอรถไม่ได้ ไม่ต้องถอนแอป ไม่ต้องใช้คอม

### 4.1 เตรียม Shizuku (ข้ามได้ถ้ามี root)

Shizuku เริ่มได้ด้วย wireless debugging ของ Android 11+ โดยไม่ต้อง root

1. เปิด Developer options (ตั้งค่า → เกี่ยวกับโทรศัพท์ → แตะ **Build number** 7 ครั้ง)
2. ต่อ Wi-Fi แล้วเปิด **Wireless debugging**
3. ติดตั้ง [Shizuku](https://github.com/RikkaApps/Shizuku/releases/latest) (หรือจาก Play/IzzyOnDroid — [ข้อ 0](#0-เครื่องมือที่ต้องโหลด--shizuku--kinginstaller)) → หัวข้อ *Start via Wireless debugging*
   → **Pairing** → ในหน้า Wireless debugging เลือก **Pair device with pairing code** → กรอกรหัส 6 หลัก
   จากการแจ้งเตือนของ Shizuku
4. กลับมาที่ Shizuku → **Start** → ด้านบนต้องขึ้น "Shizuku is running"

Wireless debugging จะดับเองเมื่อรีบูตเครื่อง ซึ่งไม่เป็นไร — Shizuku ต้องรันแค่ตอนทำขั้นนี้
และตอนใช้ input backend แบบ Shizuku เท่านั้น

### 4.2 กดปุ่ม

1. เปิด AutoBridge → หน้า **AutoBridge on Android Auto**
2. อ่านสถานะ 3 บรรทัดบนหน้านี้ (`ProjectionSetupActivity`):
   - `Status: visible on Android Auto` / `hidden (sideloaded)`
   - `Recorded installer: …`
   - `Shizuku: ready / needs permission / not running`
3. ถ้าขึ้น `needs permission` กด **Grant Shizuku permission** แล้วเลือก *Allow all the time*
4. กด **Enable on Android Auto** → รอข้อความ `Done via ROOT.` หรือ `Done via SHIZUKU. Reconnect Android Auto…`

ลำดับสิทธิ์ที่โค้ดเลือกให้เอง: **root ก่อน** (`su -c id` ได้ `uid=0`) ถ้าไม่มีจึงใช้ **Shizuku**
และเมื่อรันคำสั่งเสร็จ แอปจะ **อ่านค่า installer กลับมายืนยัน** ด้วย `pm list packages -i` ไม่เชื่อแค่
exit code ของคำสั่ง — ถ้าเขียนไม่ติดจะรายงาน `installer still …` ตรง ๆ

---

## 5. ขั้นบังคับที่แอปทำแทนไม่ได้: Unknown sources

สวิตช์นี้อยู่ในแอป Android Auto เอง แอปอื่นแตะไม่ได้

1. ตั้งค่า → แอป → **Android Auto** → เลื่อนล่างสุด แตะบรรทัด **Version** 10 ครั้ง → ตอบ OK
2. เมนูสามจุดมุมขวาบน → **Developer settings** → เปิด **Unknown sources**
3. (ถ้ามี) ตั้ง **Application mode** เป็น *Developer* หรือ *Release*
4. **ถอดสายแล้วต่อรถใหม่** → **"AutoBridge Browser"** จะปรากฏในรายการแอปของ Android Auto
5. ถ้ายังไม่เห็นไอคอน เปิด **Customize launcher** ในตั้งค่า Android Auto แล้วติ๊ก AutoBridge

---

## 6. ตรวจสอบว่าสำเร็จ

```bash
adb shell pm list packages -i dev.autobridge
# ต้องเห็น: package:dev.autobridge  installer=com.android.vending
```

ไม่มีคอมก็ดูได้จากหน้า **AutoBridge on Android Auto** — บรรทัด `Recorded installer:` ต้องเป็น
`com.android.vending` และบรรทัดแรกต้องเป็น `visible on Android Auto`

---

## 7. Play Protect, MIUI และ Restricted settings

- **Play Protect เตือน "ไม่รู้จักผู้พัฒนาแอปนี้"** → รายละเอียดเพิ่มเติม → **ติดตั้งต่อไป**
  ใช้ release ที่เซ็นด้วยคีย์คงที่ของโปรเจกต์จะเจอน้อยกว่า debug build
- **Play Protect บล็อกแข็ง / ถอนแอปทิ้งเอง** → Play Store → รูปโปรไฟล์ → **Play Protect** → ⚙ →
  ปิด **สแกนแอปด้วย Play Protect** → ลง → เปิดกลับ
  ที่โดนเพราะโปรไฟล์สิทธิ์ของแอป (AccessibilityService + MediaProjection + `RECORD_AUDIO` +
  ลงจากไฟล์) ไม่ใช่เพราะ APK มีอะไรแปลก
- **MIUI / HyperOS** → Developer options ต้องเปิด **Install via USB** และ
  **USB debugging (Security settings)** ไม่งั้น `adb install` ถูกปฏิเสธ (เป็นสาเหตุที่ connected test
  ของโปรเจกต์นี้รันบนเครื่องจริงไม่ได้) และ Xiaomi ยังมีตัวสแกน APK ของตัวเองแยกจาก Play Protect
- **Android 13+ Restricted settings** → แอปที่ลงจากไฟล์จะเปิด Accessibility ให้ไม่ได้ (ขึ้น
  "การตั้งค่าที่ถูกจำกัด") ซึ่งทำให้ input backend ฝั่ง accessibility ใช้ไม่ได้ แก้ที่ ตั้งค่า → แอป →
  AutoBridge → ⋮ → **อนุญาตการตั้งค่าที่ถูกจำกัด** แล้วย้อนไปเปิด accessibility อีกครั้ง
- การลงผ่าน adb หรือ Shizuku ไม่ผ่าน UI ของ package installer จึงไม่มีป๊อปอัพ Play Protect ตอนลง

---

## 8. แก้ปัญหา

| อาการ | สาเหตุที่พบบ่อย |
|---|---|
| ไม่มีหน้า "AutoBridge on Android Auto" ในแอป | ลง flavor `safe` อยู่ → ใช้ `personal` หรือ `lab` |
| กด Enable แล้วขึ้น `No root or Shizuku permission available` | Shizuku ไม่รัน หรือยังไม่ให้ permission / ไม่มี `su` |
| ขึ้น `reinstall via … did not run` | คำสั่งไม่ได้รันเลย — เริ่ม Shizuku ใหม่แล้วลองอีกครั้ง |
| ขึ้น `installer still …` | เขียนค่าไม่ติด ลองซ้ำ หรือใช้ KingInstaller Root Trick แทน |
| installer = `com.android.vending` แล้วแต่ยังไม่โชว์บนจอรถ | ยังไม่เปิด Unknown sources หรือยังไม่ต่อรถใหม่ |
| โชว์ครั้งแรกแล้วหายหลังอัปเดตแอป | ลงทับด้วย adb/ตัวจัดการไฟล์ → installer ถูกเขียนทับ ให้ทำขั้น 2 ซ้ำ |
| ลงทับไม่ได้ ขึ้น signature mismatch | สลับ debug ↔ release หรือคีย์ต่างเครื่อง → ถอนแอปก่อนลง |
| อยากทดสอบโดยไม่มีรถ | DHU 2.0 ต่อ Android Auto รุ่นใหม่ได้ แต่ต้อง force-stop Android Auto ก่อนเริ่ม DHU |

---

## 9. ข้อควรรู้เรื่องนโยบาย

- เส้นทาง projection ใช้ SDK ที่ไม่เป็นทางการ (`aauto.aar`) Google Play ไม่รับ จึงมีเฉพาะ flavor
  `personal` และ `lab` — flavor `safe` ที่ใช้ขึ้น Play ไม่มีโค้ดส่วนนี้คอมไพล์เข้าไปเลย
- การเขียน install source เป็น Play Store ทำเพื่อ **ใช้งานส่วนตัว/ทดสอบ** เท่านั้น ไม่มีการแก้หรือ
  เซ็น APK ใหม่ และไม่ได้หลบเลี่ยง DRM
- ใช้งานตอน **จอดรถ** เท่านั้น ดูหัวข้อ Safety first ใน [README](README.md)
