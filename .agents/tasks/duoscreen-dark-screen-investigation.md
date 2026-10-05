# Duo Screen — "Dark Phone Screen" (ดับหน้าจอมืดถือได้ไหม) Investigation

Read-only investigation. No code was modified. Every claim below is grounded in a named
file/symbol or in commit `acb4474` ("Keep a Duo Screen session alive with the phone's panel dark").

คำถามผู้ใช้: **"duo screen มันทำให้รองรับการทำงานแบบ ดับหน้าจอมืดถือได้ไหม"** —
Duo Screen รองรับการใช้งานโดยที่หน้าจอโทรศัพท์ดับ/มืด ขณะที่ session ยังทำงานต่อ (จอรถยังทำงาน
และถือเครื่องโดยหน้าจอมืดได้) หรือไม่?

---

## สรุปคำตอบ (อ่านก่อน)

**ได้ "แบบมีเงื่อนไข" — ใช่ แต่ไม่ใช่การปิดจอด้วยปุ่ม power.**

- **หน้าจอ "มืด" ได้จริง** โดย session ยังทำงานต่อ แต่สิ่งที่เกิดคือ **ตัวพาเนล (physical panel)
  ถูกสั่งดับ ขณะที่ display ของระบบยัง "ตื่น" อยู่** ไม่ใช่การ sleep/lock เครื่องจริง ๆ
  (ดู `ScreenPowerController` KDoc และ commit `acb4474`). เครื่องถูกกด WakeLock ค้างไว้ตลอด session
  เพื่อไม่ให้ระบบหยุด resume แอปในแต่ละ pane.
- **ทำไมต้องทำแบบนี้:** แต่ละ pane ของ Duo Screen รันบน **untrusted VirtualDisplay**
  (`DuoScreenDisplays`). แอปทั่วไปขอ `FLAG_TRUSTED` / `FLAG_ALWAYS_UNLOCKED` ไม่ได้ (ต้องมี
  `ADD_TRUSTED_DISPLAY` ซึ่ง platform ปฏิเสธ) ดังนั้นเมื่อเครื่อง sleep หรือ lock จริง ๆ
  ระบบจะหยุด resume แอปในพาเนลเหล่านั้น และจอรถจะค้าง/ไม่อัปเดต (commit message + `CarScreenPower` KDoc).
  ทางออกที่แอปทำได้คือ "display ตื่น แต่พาเนลมืด" เท่านั้น.
- **การปิดพาเนลให้มืดจริง ต้องใช้ Shizuku** (hidden API ผ่าน SurfaceControl/DisplayControl
  `setDisplayPowerMode`). ถ้าไม่มี Shizuku หรือ ROM ไม่รองรับ จะ fallback ไปเป็น
  "public dimming" (หรี่จอ) แทน — ยังสว่างอยู่แต่หรี่.
- **ถ้ากดปุ่ม power ปิดจอเองจะพัง:** การกดปุ่ม power = sleep/lock จริง ซึ่งเป็นสิ่งที่ WakeLock
  พยายามกันไว้ และเป็นสาเหตุที่ทำให้ pane หยุดทำงาน. "ดับจอ" ที่รองรับคือดับผ่านฟีเจอร์
  auto-dim/panel-off ของแอปเท่านั้น ไม่ใช่ปุ่ม power.
- **รองรับเฉพาะ flavor `personal` และ `lab`** (sideload) — flavor "safe" ไม่คอมไพล์ `:duoscreen`
  เลย (`app/build.gradle.kts`).
- **ยังไม่ได้ทดสอบบนหัวรถจริง:** commit `acb4474` ระบุชัดว่า verified ด้วย unit test + compile เท่านั้น
  เพราะ panel-off backend เป็น hidden API ขึ้นกับ OEM และเครื่องที่ต่ออยู่ปฏิเสธ adb install.

---

## หลักฐาน (Evidence)

### 1. กลไกหลัก — commit `acb4474`

Commit `acb4474` "Keep a Duo Screen session alive with the phone's panel dark" คือคำตอบที่เป็นทางการ
ของคำถามนี้. ใจความ (เรียบเรียงเพื่อให้ตรงลิขสิทธิ์):

- ก่อนหน้านี้ Duo Screen ต้องถือเครื่องให้ตื่นด้วยมือ เพราะทุก pane รันบน untrusted VirtualDisplay
  และเมื่อเครื่อง sleep/lock ระบบจะหยุด resume แอปในพาเนล จอรถจึงค้าง.
- `FLAG_TRUSTED` และ `FLAG_ALWAYS_UNLOCKED` จะแก้ที่ต้นเหตุได้ แต่ platform ปฏิเสธให้ app process
  ดังนั้นเป้าหมายที่ทำได้จริงคือ **"display ตื่น แต่พาเนลมืด"** — ซึ่งเป็นเส้นทางเดียวกับที่ mirror
  ใช้อยู่แล้ว.
- `ScreenPowerController` เป็นเจ้าของ policy นี้ (WakeLock, นับถอยหลัง auto-dim, panel-only screen-off
  แบบ opt-in ผ่าน privileged, และ restore ตอน teardown).

### 2. WakeLock ค้าง session ไว้ — `ScreenPowerController`

ไฟล์ `app/src/main/java/dev/autobridge/display/ScreenPowerController.kt`:

- `startForCarSession(context)` ถูกเรียกเมื่อ Duo Screen session เริ่ม. ต่างจาก mirror ตรงที่
  **บังคับ `preventScreenSleep = true` เสมอ ไม่อ่านจาก settings** — KDoc อธิบายว่าสำหรับ Duo Screen
  "เครื่อง sleep = ระบบหยุด resume แอปในพาเนล = session หยุดทำงานทั้งหมด" ดังนั้น WakeLock เป็น
  "ส่วนหนึ่งของการรัน ไม่ใช่ตัวเลือก".
- `acquire(dim)` สร้าง `PowerManager.WakeLock` ระดับ `SCREEN_BRIGHT_WAKE_LOCK` (ตื่น) หรือ
  `SCREEN_DIM_WAKE_LOCK` (หรี่) และ `setReferenceCounted(false)` แล้ว `acquire()` ค้างไว้.
- `userActivity()` รีเซ็ตตัวนับ auto-dim เมื่อมี input ฝั่งรถ — เรียกจาก `DuoScreenController.forwardTap`
  / `forwardScroll` ผ่าน `CarScreenPower.carInput()` (commit diff).
- Ownership (`enum Owner { NONE, MIRROR, CAR_SESSION }` + `sessionActive`): session ที่ไม่ได้เริ่ม
  policy จะปิดมันไม่ได้ — `stopCarSession()` เป็น no-op ถ้า mirror เป็นเจ้าของ. แต่ `stop()`
  teardown เสมอ เพราะเป็นเส้นทางความปลอดภัยตอน MOVING/UNKNOWN.

### 3. อะไร "มืด" กันแน่ — พาเนลดับจริง ผ่าน Shizuku hidden API

- `applyIdlePolicy()` ใน `ScreenPowerController`: เมื่อ auto-dim ครบเวลา และ
  `MirrorSettings.screenOffOnAutoDim == true` จะเรียก `panel.hide()`; log ว่า
  "Privileged panel-only screen-off applied after auto-dim". ถ้าทำไม่สำเร็จจะ log
  "Privileged panel-off unavailable; falling back to public dimming" แล้ว `acquire(dim = true)`
  (หรี่จอ public แทน).
- `PanelPowerLease.hide()` (`app/.../display/PanelPowerLease.kt`): **ยึด display WakeLock ก่อน**
  แล้วค่อยสั่งปิดพาเนล และถือ lock ไว้จนกว่าจะ restore — นี่คือเหตุผลที่ display ยัง "on"
  ขณะพาเนลมืด.
- `turnOff = { ShizukuInputBackend.setPanelPower(on = false) }` →
  `ShizukuInputBackend.setPanelPower` (`app/.../input/ShizukuInputBackend.kt`) เรียกผ่าน Shizuku user
  service `setDisplayPower(on)`. **ต้องมีสิทธิ์ Shizuku** (`isPermissionGranted`) และ
  `FeaturePolicy.app.isAvailable(Feature.SHIZUKU)` ไม่งั้น return false ทันที.
- การปิดจริงอยู่ที่ `ShizukuDisplayPowerController.setDisplayPower` (`app/.../input/ShizukuTouchService.kt`):
  reflection หา `setDisplayPowerMode(IBinder, Int)` จาก `android.view.DisplayControl` หรือ
  `android.view.SurfaceControl` แล้วส่ง `POWER_MODE_OFF = 0` / `POWER_MODE_NORMAL = 2` ไปที่
  default display token. KDoc ย้ำว่า API นี้ย้ายที่ระหว่าง Android releases จึงต้อง probe ความสามารถก่อน
  และถ้า fail จะรายงานว่าไม่รองรับ (ไม่เดาว่ารองรับ) → `isDisplayPowerControlAvailable()`.

สรุปข้อ 2: จอที่ "มืด" คือ **พาเนลจริงถูกสั่ง power mode = OFF** ไม่ใช่ overlay สีดำ แต่ display
ของระบบยังตื่น (ถูก WakeLock ค้าง). Touch ของผู้ใช้ที่พาเนลโทรศัพท์ไม่เกี่ยวกับ session — การควบคุม
Duo Screen ทำผ่าน **จอรถ** (gesture จาก `SurfaceCallback` → `DuoScreenController`), ส่วน
`userActivity()` จงใจ **ไม่ปลุกพาเนล** ทุกครั้งที่มี gesture ฝั่งรถ (ดู comment ใน `userActivity()`:
"Do not wake the phone panel for every car-side gesture").

### 4. การ wiring: hook เป็น no-op จนกว่า :app จะติดตั้ง policy

- `:duoscreen` เรียกผ่าน seam ใน `:common`: `CarScreenPower.sessionStarted/carInput/sessionEnded`
  (`common/.../power/CarScreenPower.kt`). ทุก hook เป็น **no-op จนกว่าจะมีการ `install()`** จึงไม่พัง
  ใน unit test หรือ build ที่ไม่มี projection flavor.
- `DuoScreenController` (commit diff): เรียก `CarScreenPower.sessionStarted(context)` เมื่อ session เริ่ม
  (ทั้งเส้น resume และเส้นสร้างใหม่), `CarScreenPower.sessionEnded()` ใน `stop()`, และ
  `CarScreenPower.carInput()` ใน `forwardTap`/`forwardScroll`. จงใจ **ไม่** เรียกใน `detach()`
  เพราะ pane ของ session ที่ detach ยังรันอยู่ และช่วง keep-alive คือตอนที่คนขับมองแผนที่โดยไม่แตะเครื่อง.
- ติดตั้ง policy ที่ `AutoBridgeApplication.onCreate()` → `CarSessionScreenPower.install()`
  (`app/.../AutoBridgeApplication.kt` บรรทัด 43). `CarSessionScreenPower` (ใน `:app`) implement
  `CarScreenPowerPolicy` และต่อ hook ไปที่ `ScreenPowerController.startForCarSession/userActivity/stopCarSession`.
- `AutoBridgeApplication` อยู่ใน `app/src/main` (ทุก flavor register policy) แต่ **session จริง
  มีเฉพาะ personal/lab** เพราะ `:duoscreen` ถูก depend เฉพาะ `personalImplementation` /
  `labImplementation` (`app/build.gradle.kts` บรรทัด 269–270). flavor safe จึงไม่มี Duo Screen เลย
  (ไม่ compile, ไม่ merge manifest, ไม่ ship — comment บรรทัด 231–236).

### 5. การตั้งค่าที่ผู้ใช้คุมได้ และกรณีพิเศษ `CarSessionDimPolicy`

- `MirrorSettings.autoDimDelay` (enum `AutoDimDelay`: `OFF`, …, `SECONDS_30`) = เมื่อไรจะมืด;
  `MirrorSettings.screenOffOnAutoDim` (default `false`) = จะใช้ panel-off (privileged) หรือหรี่จอ
  public (`app/.../settings/MirrorSettings.kt`, persist ใน `SettingsStore` key
  `screen_off_on_auto_dim`).
- `CarSessionDimPolicy.delay()` (`app/.../display/CarSessionDimPolicy.kt`): เติมช่องว่างหนึ่งกรณี —
  ถ้าเปิด panel-off แต่ตั้ง delay = `OFF` จะไม่มีอะไร trigger ให้ดับ (จอค้างสว่างทั้งทริป) จึงบังคับ
  fallback = `SECONDS_30`. มี unit test ครอบ (`CarSessionDimPolicyTest`).

### 6. ข้อจำกัด/คำเตือน (caveats)

- **ต้องมี Shizuku** จึงจะดับพาเนลจริงได้; ไม่งั้นได้แค่หรี่จอ (ยังสว่าง, กินแบตมากกว่าดับ).
- **ขึ้นกับเวอร์ชัน Android / OEM:** `setDisplayPowerMode` เป็น hidden API ที่ย้ายที่ระหว่าง release;
  `ShizukuDisplayPowerController` probe ก่อนเสมอ และอาจไม่พบบาง ROM → fallback หรี่จอ.
- **แบตเตอรี่:** session ถือ WakeLock ค้างตลอด (display ไม่ยอม sleep). panel-off ลดการกินไฟของจอได้
  แต่ CPU/แอปในพาเนลยังรัน. หรี่จอ (fallback) กินไฟมากกว่า.
- **กดปุ่ม power / lock เครื่องเองจะทำให้ session พัง** — เพราะ WakeLock กันแค่ timeout ไม่ได้กัน
  การ sleep ที่ผู้ใช้สั่งเอง; เมื่อ sleep/lock จริงระบบหยุด resume pane (ตามเหตุผลใน commit).
- **สายเรียกเข้า/หน้า lock:** ไม่ได้ตรวจเจอโค้ดจัดการสายเรียกเข้าโดยตรง; เมื่อระบบเด้งหน้า lock/โทร
  ขึ้นมา (เครื่อง interactive ปกติ) พฤติกรรมบนหัวรถจริงยังไม่ยืนยัน — ต้องทดสอบบนอุปกรณ์.
- **restore เสมอ:** ทุกเส้น stop/unbind คืนพาเนล (`stop()`, `ShizukuInputBackend.unbind` เรียก
  `restorePanelPower()` ก่อนปล่อย binder) และมี manual `restorePhoneScreen()` + status
  "Screen restore failed; reconnect Shizuku and retry".
- **ยังไม่ทดสอบบนหัวรถจริง** (commit `acb4474`: verified by unit tests and compilation only).

---

## ข้อสรุปและคำแนะนำ

1. **ตอบผู้ใช้:** ได้ — Duo Screen รองรับการใช้งานโดยหน้าจอโทรศัพท์มืดได้ แต่คือ "พาเนลดับ
   ขณะ display ยังตื่น" ผ่าน auto-dim + panel-off (ต้องมี Shizuku) ไม่ใช่การกดปุ่ม power ปิดจอ.
   session ยังทำงานเพราะ WakeLock ค้าง display ไว้ มิฉะนั้น pane บน VirtualDisplay จะหยุด resume.
2. **ให้ผู้ใช้เปิดใช้:** ตั้ง `screenOffOnAutoDim` (panel-off) = on และ auto-dim delay เป็นค่าหนึ่ง
   (ถ้าลืมตั้ง delay ระบบใช้ 30s ให้อัตโนมัติ). ต้องให้สิทธิ์ Shizuku ก่อน.
3. **เตือนผู้ใช้:** อย่ากดปุ่ม power เพื่อดับจอเอง (จะทำให้ session หยุด); ใช้ฟีเจอร์ auto-dim แทน.
   ถ้าจอแค่หรี่ไม่ดับ แปลว่า ROM/Shizuku ไม่รองรับ panel-off จึง fallback เป็น public dimming.
4. **ข้อควรทดสอบบนอุปกรณ์จริง** (ยืนยันไม่ได้จาก source อย่างเดียว): พฤติกรรมเมื่อมีสายเรียกเข้า/
   หน้า lock เด้ง, การกินแบตจริงระหว่าง panel-off, และว่า `setDisplayPowerMode` resolve บนหัวรถ/ROM
   เป้าหมายหรือไม่ — commit เองก็ระบุว่ายังไม่ทดสอบบน head unit จริง.
5. รองรับเฉพาะ flavor **personal/lab** เท่านั้น ตรงกับที่ผู้ใช้ขอ "เพิ่ม เผื่อ personal".

ไม่มีการแก้ไขโค้ด (read-only investigation).
