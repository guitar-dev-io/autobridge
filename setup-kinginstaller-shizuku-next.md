# ตั้งค่า Shizuku-Next และ KingInstaller ครั้งแรก 🛠️

> **ฉบับร่าง** · อัปเดต: 2026-10-11 · ชื่อปุ่มตรวจกับเครื่องจริง (Xiaomi Android 16 / HyperOS) ที่ลง Shizuku-Next `v14.0.17-next` และ KingInstaller `v2.3`

คู่มือนี้พาตั้งเครื่องมือสองตัวตั้งแต่โหลดจนใช้งานครั้งแรก แล้วจบที่การลง AutoBridge ให้โชว์บนจอ Android Auto
ภาพรวมทั้งหมดและทางเลือกอื่น ๆ (ปุ่มในแอป, root) ดูที่ [quick-install.md](quick-install.md)

ทั้งสองตัวเป็นแอปของผู้พัฒนาอื่น **ไม่ได้มาพร้อม AutoBridge** ให้โหลดจากต้นทางเท่านั้น

| เครื่องมือ | โหลดที่ | ไฟล์ |
|---|---|---|
| **Shizuku-Next** (fork ของ Shizuku) | [Releases](https://github.com/rushiranpise/Shizuku-Next/releases/latest) | `shizuku-v…-next.apk` (**ไม่ใช่**ตัวที่ลงท้าย `-debug`) |
| **KingInstaller** | [Releases](https://github.com/fcaronte/KingInstaller/releases/latest) | `KingInstaller-v….apk` |

---

## ลำดับที่ต้องทำ

```
1. เตรียมเครื่อง ──► 2. ลง + เริ่ม Shizuku-Next ──► 3. ลง KingInstaller
        │                                               │
        └── (ข้าม 2 ได้ ถ้าไม่เปิดสวิตช์แล้วผ่าน) ──────────┘
                                                        ▼
  6. ต่อรถ ◄── 5. เปิดแหล่งที่มาที่ไม่รู้จัก ◄── 4. ลง AutoBridge ผ่าน KingInstaller
```

> **ต้องใช้ Shizuku-Next ไหม?** ไม่จำเป็นเสมอ ลอง **Install as king** แบบไม่เปิดสวิตช์ก่อนได้ ซึ่งไม่ต้องใช้อะไรเพิ่ม
> แต่แนะนำให้ตั้งไว้เลย เพราะถ้าไม่ผ่านต้องใช้สวิตช์ **Shizuku** และปุ่ม **ให้แสดงบน Android Auto**
> ในแอป AutoBridge ก็ใช้ Shizuku ซ่อมค่าโดยไม่ต้องถอนแอป

> ภาพประกอบทีละขั้นจากเครื่องจริงอยู่ใน canvas ออกแบบ (ชุด "คู่มือตั้งค่าครั้งแรก")

---

## 1. เตรียมเครื่อง (ทำครั้งเดียว)

1. **เปิด Developer options** — ตั้งค่า → เกี่ยวกับโทรศัพท์ → แตะ **Build number** 7 ครั้ง
   (Xiaomi/HyperOS: แตะ **OS version** แทน)
2. **อนุญาตให้ลงแอปจากไฟล์** — ตอนเปิดไฟล์ APK ครั้งแรก ระบบจะถาม "ติดตั้งแอปที่ไม่รู้จัก"
   ให้อนุญาตกับเบราว์เซอร์หรือตัวจัดการไฟล์ที่ใช้เปิด
3. **ต่อ Wi-Fi** — การเริ่ม Shizuku-Next แบบปกติใช้ Wireless debugging ซึ่งต้องมี Wi-Fi
   (ไม่ต้องมีอินเทอร์เน็ต แค่ต่อเครือข่ายไว้ก็พอ เช่น hotspot ของอีกเครื่อง)

---

## 2. Shizuku-Next — ติดตั้งและเริ่มครั้งแรก

### 2.1 ติดตั้ง

1. ถ้าเครื่องมี **Shizuku ตัวเดิม** (RikkaApps หรือ fork อื่น) อยู่แล้ว **ให้ถอนออกก่อน**
   Shizuku-Next ใช้ชื่อแพ็กเกจเดียวกัน (`moe.shizuku.privileged.api`) แต่เซ็นด้วยกุญแจคนละชุด จึงลงทับไม่ได้
   ถอนเสร็จแล้วควรรีบูตหนึ่งครั้ง เพราะเซอร์วิสของตัวเก่าอาจยังค้างอยู่จนกว่าจะรีบูต
2. โหลด `shizuku-v…-next.apk` จาก [Releases](https://github.com/rushiranpise/Shizuku-Next/releases/latest) แล้วติดตั้ง

### 2.2 เริ่มครั้งแรกด้วย Wireless debugging (Android 11+)

1. เปิด Shizuku-Next → แท็บ **Home** → กด **เริ่ม** (Start) วิธีเริ่มค่าเริ่มต้นคือ Wireless debugging
2. ถ้ายังไม่เคยจับคู่ แอปจะพาเข้าหน้าจับคู่ให้เอง (ไม่มีปุ่ม Pair แยกเหมือน Shizuku ตัวเดิม)
   - ระบบจะเปิดหน้า **Wireless debugging** → แตะ **Pair device with pairing code**
   - Shizuku-Next อ่านรหัสและพอร์ตจากหน้าต่างของระบบได้เอง ถ้าอ่านไม่ได้ ให้กด **Manual**
     แล้วพิมพ์รหัส 6 หลักลงในการแจ้งเตือนของ Shizuku-Next
3. **Android 16 ขึ้นไป:** ถ้าถามสิทธิ์ **อุปกรณ์ในเครือข่ายใกล้เคียง/Local network** ให้อนุญาต
   ไม่เช่นนั้นจะเชื่อมต่อ Wireless debugging ไม่ได้
4. กลับมาที่ Home → การ์ดบนสุดต้องขึ้น **Running** และบรรทัด Current เป็น **Wireless**, UID **2000 (shell)**

> ถ้าจับคู่อัตโนมัติไม่ผ่านตั้งแต่ครั้งแรก README ของ Shizuku-Next ระบุว่าเครื่องที่เพิ่งลงใหม่อาจต้องใช้การจับคู่แบบ
> **Manual** หรือสั่ง `adb shell pm grant …` จากคอมหนึ่งครั้ง เพื่อให้แอปได้สิทธิ์ `WRITE_SECURE_SETTINGS` ก่อน

### 2.3 วิธีเริ่มอื่น (เลือกตามเครื่อง)

อยู่ใต้ปุ่ม **Other start methods** ในหน้า Home

| วิธี | ใช้เมื่อ | หมายเหตุ |
|---|---|---|
| **Wireless debugging** | เครื่องทั่วไป Android 11+ | วิธีหลัก ต้องมี Wi-Fi |
| **Start without Wi-Fi (experimental)** | อยู่ในรถ ไม่มี Wi-Fi | สวิตช์อยู่ท้ายแท็บ **Settings** อาศัยบั๊กของระบบ อาจใช้ไม่ได้หลังอัปเดต OS และอาจทิ้ง Wireless debugging เปิดค้างไว้ |
| **Start via USB debugging** | มีคอม | เปิด **USB debugging** แล้วต่อสาย กดอนุญาตตอนเครื่องถาม |
| **Start via Computer** | มีคอม | แตะเพื่อคัดลอกคำสั่ง adb ไปรันบนคอม |
| **Start via System UID** | Samsung เท่านั้น | |
| **Root** | เครื่องรูทแล้ว | ไม่ต้องจับคู่ |

### 2.4 หลังรีบูต

Wireless debugging จะดับเองทุกครั้งที่รีบูต Shizuku-Next จึงหยุดตามไปด้วย
- เปิด **เริ่มต้นเมื่อบูตเครื่อง** (Start on boot) ในแท็บ Settings ได้ (ใช้ได้เฉพาะ Wireless debugging หรือ Root และจะรอ Wi-Fi ก่อน)
- หรือเปิดแอปแล้วกด **เริ่ม** ใหม่เมื่อจะใช้

สำหรับ AutoBridge ต้องให้ Shizuku ทำงานแค่ตอน **ลงหรืออัปเดตแอป** และตอนใช้ input backend แบบ Shizuku
ไม่ต้องรันตลอดเวลา

---

## 3. KingInstaller — ติดตั้งและเปิดครั้งแรก

1. โหลด `KingInstaller-v….apk` จาก [Releases](https://github.com/fcaronte/KingInstaller/releases/latest) แล้วติดตั้ง
   (ไม่มีบน Google Play ถ้าเจอใน Play Store ไม่ใช่ตัวจริง)
2. ให้สิทธิ์ Shizuku: เปิด Shizuku-Next → แท็บ **Apps** → เปิดสวิตช์ท้ายแถว **KingInstaller**
   (ถ้า KingInstaller ขึ้นหน้าต่างขอสิทธิ์ Shizuku เองก็กด **อนุญาต** ได้เลย)
3. เปิด KingInstaller การ์ดบนสุด **Welcome to Installer!** จะบอกว่าเครื่องนี้ควรใช้วิธีไหน
   เช่นบนเครื่องที่ใช้ Google Package Installer จะขึ้นว่าให้ใช้วิธีที่ไม่ต้องรูท
4. เมนู ⋮ มุมขวาบนมีลิงก์ **Download Shizuku Next** และ **Check for updates**

---

## 4. ลง AutoBridge ผ่าน KingInstaller

1. โหลด `app-personal-release.apk` (เลือกตัวให้ถูกตาม [quick-install.md ข้อ 1](quick-install.md#1-เลือก-apk-ให้ถูกตัว))
2. ใน KingInstaller → แตะ **Select APK to install:** → เลือกไฟล์ APK
3. ในการ์ด **Installation Method** เลือกสวิตช์ (ลองตามลำดับ ถ้าวิธีแรกผ่านก็หยุดได้)

   | ลำดับ | สวิตช์ | ต้องมี |
   |---|---|---|
   | 1 | ไม่เปิดสวิตช์ไหนเลย (README เรียกว่า Classic) | — |
   | 2 | **Shizuku** | Shizuku-Next ที่ขึ้น Running และให้สิทธิ์ KingInstaller แล้ว (ข้อ 2, 3) |
   | 3 | **Root** | เครื่องรูท |
   | — | **Oppo/Realme/OnePlus** | ใช้เฉพาะเครื่องยี่ห้อนี้ |

4. กด **Install as king** → ยืนยันในหน้าติดตั้งของระบบ
5. **ตรวจผล** — หน้าแรกของ KingInstaller → **Check and Reinstall App** → ค้นหา AutoBridge → แตะชื่อแอป
   - **Installing Installer** ต้องเป็น **Google Play Store (com.android.vending)** ← ข้อนี้สำคัญที่สุด
   - กรอบเขียว **This app should work in Android Auto** = ผ่าน
   - กรอบแดง **This app might not be available in Android Auto** = ยังไม่ผ่าน
     กด **Reinstall with KingInstaller** แล้วลองสวิตช์ถัดไปในตาราง

> **Xiaomi / POCO / Redmi (MIUI, HyperOS):** README ของ KingInstaller ระบุว่าวิธี Shizuku **มักไม่ผ่าน** บนเครื่องกลุ่มนี้
> (ตัว Shizuku-Next เองเริ่มได้ปกติ) ถ้าไม่เปิดสวิตช์แล้วไม่ผ่าน ให้ใช้ **Root** หรือใช้ปุ่ม **ให้แสดงบน Android Auto** ในแอป AutoBridge แทน
> ([quick-install.md ข้อ 4](quick-install.md#4-เส้นทาง-b--ปุ่มในแอป-root-หรือ-shizuku))

---

## 5. เปิดแหล่งที่มาที่ไม่รู้จักใน Android Auto (ทำครั้งเดียว)

1. เปิดตั้งค่า Android Auto (ปุ่ม **AA** ในหน้าแรกของ KingInstaller พาไปได้เลย หรือ ตั้งค่า → แอป → Android Auto)
2. เลื่อนล่างสุด แตะบรรทัด **เวอร์ชัน** 10 ครั้ง → ตอบ **ตกลง**
3. เมนู ⋮ → **การตั้งค่าสำหรับนักพัฒนา** → ติ๊ก **แหล่งที่มาที่ไม่รู้จัก**

---

## 6. ใช้งานครั้งแรกบนรถ

1. ถอดสาย แล้วต่อรถใหม่ (หรือเปิด DHU)
2. หา **AutoBridge Browser** ในรายการแอปของ Android Auto
   ถ้าไม่เห็น ให้เปิด **Customize launcher** ในตั้งค่า Android Auto แล้วติ๊ก AutoBridge Browser
3. ในมือถือ เปิด AutoBridge → ตั้งค่า → **รถและแอป** → **Bridge Web บน Android Auto** → ส่วนสถานะต้องขึ้น
   **บน Android Auto: แสดงอยู่** และ **ตัวติดตั้งที่บันทึกไว้: `com.android.vending`** (คือ Play Store)

---

## อัปเดตเวอร์ชันถัดไป

**อัปเดตผ่าน KingInstaller ทุกครั้ง** ถ้าลงทับด้วยตัวจัดการไฟล์ หรือ `adb install`
ค่า Installing Installer จะกลับไปเป็นตัวนั้น แล้ว AutoBridge จะหายจากจอรถอีก

- ตัวอัปเดตในแอป (ตั้งค่า → เกี่ยวกับ) โหลด APK ให้แล้วเปิดหน้าต่าง **"ติดตั้งด้วย…"** ให้เลือก **KingInstaller**
- ถ้าเผลอลงทับไปแล้ว ให้เริ่ม Shizuku-Next แล้วเข้า **Bridge Web บน Android Auto** → กด **ให้แสดงบน Android Auto**
  เพื่อซ่อมค่าโดยไม่ต้องถอนแอป (ถ้าสถานะ Shizuku ขึ้นว่าต้องขอสิทธิ์ ให้กด **ขอสิทธิ์ Shizuku** ก่อน)

---

## แก้ปัญหาที่พบบ่อย

| อาการ | สาเหตุ / วิธีแก้ |
|---|---|
| ลง Shizuku-Next ไม่ได้ ขึ้นว่าแพ็กเกจขัดแย้ง | ยังมี Shizuku ตัวเดิมอยู่ ถอนออกก่อน (ข้อ 2.1) |
| Shizuku-Next จับคู่ไม่สำเร็จ | ปิด/เปิด Wireless debugging ใหม่ ใช้ **Manual** และบน Android 16+ ต้องอนุญาต Local network |
| Shizuku-Next หยุดเองหลังรีบูต | ปกติ เพราะ Wireless debugging ดับตอนรีบูต เปิด **Start on boot** หรือกด Start ใหม่ |
| เปิดสวิตช์ Shizuku ใน KingInstaller แล้วไม่ผ่าน | เช็กว่า Shizuku-Next ขึ้น Running และสวิตช์ของ KingInstaller ในแท็บ **Apps** เปิดอยู่ |
| ตรวจแล้วขึ้นกรอบแดง | ลองสวิตช์ถัดไปในตาราง ข้อ 4 หรือใช้ปุ่มในแอป AutoBridge |
| ขึ้นกรอบเขียวแล้ว แต่ไม่เห็นบนจอรถ | ยังไม่เปิด แหล่งที่มาที่ไม่รู้จัก (ข้อ 5) หรือยังไม่ได้ต่อรถใหม่ |
| MIUI ไม่ยอมให้ติดตั้ง | เปิด **Install via USB** / ปิด **MIUI optimization** ใน Developer options — ดู [quick-install.md ข้อ 7](quick-install.md#7-play-protect-miui-และ-restricted-settings) |

---

**ข้อควรรู้เรื่องความปลอดภัย:** Shizuku-Next ให้สิทธิ์ระดับ ADB กับแอปที่เราอนุญาต ให้อนุญาตเฉพาะแอปที่ไว้ใจ
และปิดตัวเลือก **พอร์ตถาวร (persistent port)** ถ้าไม่ได้ใช้ เพราะพอร์ตนั้นจะรับการเชื่อมต่อจากเครื่องอื่นในเครือข่ายเดียวกันได้
