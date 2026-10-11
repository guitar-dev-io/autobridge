# ตั้งค่า Shizuku-Next และ KingInstaller ครั้งแรก 🛠️

> **ฉบับร่าง** · อัปเดต: 2026-10-11 · ตรวจกับ Shizuku-Next `v14.0.17-next` และ KingInstaller `v2.3`

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
        └── (ข้าม 2 ได้ ถ้าวิธี Classic ผ่าน) ───────────────┘
                                                        ▼
            6. ต่อรถ ◄── 5. เปิด Unknown sources ◄── 4. ลง AutoBridge ผ่าน KingInstaller
```

> **ต้องใช้ Shizuku-Next ไหม?** ไม่จำเป็นเสมอ KingInstaller ให้ลองวิธี **Classic** ก่อน ซึ่งไม่ต้องใช้อะไรเพิ่ม
> Shizuku-Next ใช้เมื่อ Classic ไม่ผ่าน แต่แนะนำให้ตั้งไว้เลย เพราะตัว **Auto-Fixer** ของ KingInstaller
> ใช้ Shizuku ซ่อมค่า installer หลังติดตั้งให้เองได้

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

1. เปิด Shizuku-Next → แท็บ **Home** → เลือกวิธีเริ่มเป็น **Wireless debugging** → กด **Start**
2. ถ้ายังไม่เคยจับคู่ แอปจะพาเข้าหน้าจับคู่ให้เอง (ไม่มีปุ่ม Pair แยกเหมือน Shizuku ตัวเดิม)
   - ระบบจะเปิดหน้า **Wireless debugging** → แตะ **Pair device with pairing code**
   - Shizuku-Next อ่านรหัสและพอร์ตจากหน้าต่างของระบบได้เอง ถ้าอ่านไม่ได้ ให้กด **Manual**
     แล้วพิมพ์รหัส 6 หลักลงในการแจ้งเตือนของ Shizuku-Next
3. **Android 16 ขึ้นไป:** ถ้าถามสิทธิ์ **อุปกรณ์ในเครือข่ายใกล้เคียง/Local network** ให้อนุญาต
   ไม่เช่นนั้นจะเชื่อมต่อ Wireless debugging ไม่ได้
4. กลับมาที่ Home → ต้องขึ้นสถานะว่า **กำลังทำงาน (running)**

> ถ้าจับคู่อัตโนมัติไม่ผ่านตั้งแต่ครั้งแรก README ของ Shizuku-Next ระบุว่าเครื่องที่เพิ่งลงใหม่อาจต้องใช้การจับคู่แบบ
> **Manual** หรือสั่ง `adb shell pm grant …` จากคอมหนึ่งครั้ง เพื่อให้แอปได้สิทธิ์ `WRITE_SECURE_SETTINGS` ก่อน

### 2.3 วิธีเริ่มอื่น (เลือกตามเครื่อง)

| วิธี | ใช้เมื่อ | หมายเหตุ |
|---|---|---|
| **Wireless debugging** | เครื่องทั่วไป Android 11+ | วิธีหลัก ต้องมี Wi-Fi |
| **Start without Wi-Fi** *(ทดลอง)* | อยู่ในรถ ไม่มี Wi-Fi | ต้องเปิดในแท็บ **Labs/Settings** ก่อน อาศัยบั๊กของระบบ อาจใช้ไม่ได้หลังอัปเดต OS และอาจทิ้ง Wireless debugging เปิดค้างไว้ |
| **Start via USB debugging** | มีคอม | เปิด **USB debugging** แล้วต่อสาย กดอนุญาตตอนเครื่องถาม |
| **Root** | เครื่องรูทแล้ว | ไม่ต้องจับคู่ |

### 2.4 หลังรีบูต

Wireless debugging จะดับเองทุกครั้งที่รีบูต Shizuku-Next จึงหยุดตามไปด้วย
- เปิด **Start on boot** ในแท็บ Settings ได้ (ใช้ได้เฉพาะ Wireless debugging หรือ Root และจะรอ Wi-Fi ก่อน)
- หรือเปิดแอปแล้วกด **Start** ใหม่เมื่อจะใช้

สำหรับ AutoBridge ต้องให้ Shizuku ทำงานแค่ตอน **ลงหรืออัปเดตแอป** และตอนใช้ input backend แบบ Shizuku
ไม่ต้องรันตลอดเวลา

---

## 3. KingInstaller — ติดตั้งและเปิดครั้งแรก

1. โหลด `KingInstaller-v….apk` จาก [Releases](https://github.com/fcaronte/KingInstaller/releases/latest) แล้วติดตั้ง
   (ไม่มีบน Google Play ถ้าเจอใน Play Store ไม่ใช่ตัวจริง)
2. เปิด KingInstaller ถ้าเริ่ม Shizuku-Next ไว้แล้ว จะมีหน้าต่างขอสิทธิ์ Shizuku ให้กด **อนุญาต (Allow)**
   - ถ้าหน้าต่างไม่ขึ้น (พบบ่อยบน Android 16) ให้เปิด Shizuku-Next → แท็บ **Apps** → เปิดสวิตช์ของ KingInstaller เอง

---

## 4. ลง AutoBridge ผ่าน KingInstaller

1. โหลด `app-personal-release.apk` (เลือกตัวให้ถูกตาม [quick-install.md ข้อ 1](quick-install.md#1-เลือก-apk-ให้ถูกตัว))
2. ใน KingInstaller → แตะไอคอน **โฟลเดอร์** → เลือกไฟล์ APK
3. ลองทีละวิธีจากบนลงล่าง ถ้าวิธีแรกผ่านก็หยุดได้

   | ลำดับ | วิธี | สวิตช์ที่ต้องเปิด | ต้องมี |
   |---|---|---|---|
   | 1 | **Classic** | ไม่เปิดอะไรเลย | — |
   | 2 | **Shizuku Trick** | **Shizuku Trick** | Shizuku-Next ที่ทำงานอยู่ (ข้อ 2) |
   | 3 | **Root Trick** | **Root Trick** | เครื่องรูท |

4. กด **Install** → ยืนยันในหน้าติดตั้งของระบบ
5. **ตรวจผล** — เปิด **App Diagnostic Checker** ใน KingInstaller → เลือก AutoBridge
   - **Installed by** ต้องเป็น **Play Store** ← ข้อนี้สำคัญที่สุด
   - **Requested by** ควรเป็น **Package Installer**
   - ถ้า Installed by ยังไม่ใช่ Play Store ให้ลงซ้ำด้วยวิธีถัดไปในตาราง

> **Xiaomi / POCO / Redmi (MIUI, HyperOS):** KingInstaller ระบุว่า Shizuku Trick **มักไม่ผ่าน** บนเครื่องกลุ่มนี้
> ถ้า Classic ไม่ผ่านให้ใช้ **Root Trick** หรือใช้ปุ่ม **ให้แสดงบน Android Auto** ในแอป AutoBridge แทน
> ([quick-install.md ข้อ 4](quick-install.md#4-เส้นทาง-b--ปุ่มในแอป-root-หรือ-shizuku))

---

## 5. เปิด Unknown sources ใน Android Auto (ทำครั้งเดียว)

1. ตั้งค่า → แอป → **Android Auto** → เลื่อนล่างสุด แตะบรรทัด **Version** 10 ครั้ง → ตอบ **OK**
2. เมนูสามจุด → **Developer settings** → เปิด **Unknown sources**

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
ค่า Installed by จะกลับไปเป็นตัวนั้น แล้ว AutoBridge จะหายจากจอรถอีก

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
| KingInstaller ไม่ขึ้นหน้าต่างขอสิทธิ์ Shizuku | อนุญาตเองในแท็บ **Apps** ของ Shizuku-Next |
| Installed by ไม่ใช่ Play Store | ลองวิธีถัดไปในตาราง ข้อ 4 หรือใช้ปุ่มในแอป AutoBridge |
| Installed by ถูกแล้ว แต่ไม่เห็นบนจอรถ | ยังไม่เปิด Unknown sources (ข้อ 5) หรือยังไม่ได้ต่อรถใหม่ |
| MIUI ไม่ยอมให้ติดตั้ง | เปิด **Install via USB** / ปิด **MIUI optimization** ใน Developer options — ดู [quick-install.md ข้อ 7](quick-install.md#7-play-protect-miui-และ-restricted-settings) |

---

**ข้อควรรู้เรื่องความปลอดภัย:** Shizuku-Next ให้สิทธิ์ระดับ ADB กับแอปที่เราอนุญาต ให้อนุญาตเฉพาะแอปที่ไว้ใจ
และปิดตัวเลือก **พอร์ตถาวร (persistent port)** ถ้าไม่ได้ใช้ เพราะพอร์ตนั้นจะรับการเชื่อมต่อจากเครื่องอื่นในเครือข่ายเดียวกันได้
