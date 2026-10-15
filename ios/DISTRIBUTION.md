# แจกจ่ายแอป iOS: TestFlight, App Store และ CarPlay

บันทึกนี้รวบรวมสิ่งที่ต้องทำก่อนให้เครื่องอื่นโหลดแอป AutoBridge iOS ไปใช้, ฟีเจอร์ที่เสี่ยงโดน App Review ปฏิเสธ และการขอสิทธิ์ CarPlay

> **สถานะ (2026-10-08):** ตัดฟีเจอร์ 🔴 ออกจาก build สำหรับ store แล้ว ส่วน 🟡 ยังอยู่ครบ
>
> **Build แยกเป็น 2 แบบ:**
> - **Debug:** ตอนกด Run จาก Xcode ได้ทุกฟีเจอร์
> - **Release:** ตอน Archive เพื่อส่ง TestFlight หรือ App Store ตั้งค่า `AUTOBRIDGE_STORE` (ใน `project.yml`) จึงตัดฟีเจอร์ 🔴 ออก
>
> โค้ดอ่านค่านี้ผ่าน `AutoBridge/App/BuildFlavor.swift`

## 1. วิธีแจกจ่าย

ทุกวิธี ยกเว้นติดตั้งตรงจาก Xcode ด้วยบัญชีฟรี ต้องใช้ **Apple Developer Program แบบเสียเงิน ($99/ปี)**

| วิธี | ใครใช้ได้ | ต้องผ่าน review ไหม | ข้อจำกัด |
|---|---|---|---|
| Xcode ติดตั้งตรง (บัญชีฟรี) | เครื่องตัวเองเท่านั้น | ไม่ | หมดอายุใน 7 วัน, ใช้ CarPlay ไม่ได้ |
| TestFlight แบบคนในทีม | สมาชิกในบัญชีนักพัฒนา สูงสุด 100 คน | ไม่ | build หมดอายุใน 90 วัน |
| TestFlight แบบลิงก์สาธารณะ | ใครก็ได้ที่มีลิงก์ สูงสุด 10,000 คน | Beta App Review (ผ่อนกว่า App Store) | build หมดอายุใน 90 วัน |
| Ad Hoc | เครื่องที่ลงทะเบียน UDID สูงสุด 100 เครื่องต่อปี | ไม่ | ต้องเก็บ UDID ของแต่ละเครื่อง |
| App Store | ทุกคน | App Review เต็มรูปแบบ | ดูหัวข้อ 3 |

**แนะนำ:** เริ่มจาก TestFlight แบบคนในทีม ไม่ต้องผ่าน review จึงใช้ build เต็มที่มีทุกฟีเจอร์ได้

ค่าที่ตั้งไว้ใน `project.yml`:
- `DEVELOPMENT_TEAM`: `V9Q6ZN9L53` ต้องเป็นทีมที่จ่ายเงินแล้ว
- `PRODUCT_BUNDLE_IDENTIFIER`: `dev.autobridge`

## 2. สิ่งที่ยังขาดก่อนอัปโหลด

- [x] **ไอคอนแอป:** ✅ เพิ่มแล้วใน `Resources/Assets.xcassets/AppIcon.appiconset` วาดใหม่จาก `assets/AutoBridge.svg` ของ Android เป็นสี่เหลี่ยมเต็ม 1024px ให้ iOS ตัดมุมเอง เดิม: โปรเจกต์ยังไม่มี `Assets.xcassets` / `AppIcon` ถ้าไม่มี การอัปโหลดขึ้น App Store Connect จะไม่ผ่าน
- [x] **Privacy manifest (`PrivacyInfo.xcprivacy`):** ✅ เพิ่มแล้วใน `Resources/PrivacyInfo.xcprivacy` เดิม: แอปใช้ `UserDefaults` ซึ่งเป็น required-reason API จึงต้องประกาศเหตุผล (`CA92.1`) ถ้าไม่มี จะได้คำเตือน ITMS-91053 หรือถูกตีกลับ
- [x] **`ITSAppUsesNonExemptEncryption = NO` ใน `Info.plist`:** ✅ เพิ่มแล้ว เดิม: แอปใช้แค่ HTTPS ของระบบ ถ้าไม่ใส่ ต้องตอบคำถามเรื่องการเข้ารหัสทุกครั้งที่อัปโหลด
- [ ] **ข้อมูลหน้าร้าน:** URL ของนโยบายความเป็นส่วนตัว, ภาพหน้าจอ, คำอธิบาย และ App Privacy (แอปไม่เก็บข้อมูลผู้ใช้)
- [ ] **บัญชีทดสอบสำหรับ reviewer:** ใส่ใน App Review notes เช่น playlist วิทยุที่ถูกกฎหมาย เพื่อให้ reviewer เห็นว่าแอปเล่นอะไรได้

## 3. ฟีเจอร์ที่เสี่ยงโดนปฏิเสธใน App Store

ฟีเจอร์ 🔴 ถูกตัดออกจาก build Release แล้ว แต่ยังอยู่ใน build Debug ส่วน 🟡 ยังอยู่ทั้งสองแบบ ถ้าอยากตัดเพิ่ม ใช้ `BuildFlavor.isStore` ได้เลย

### 🔴 เสี่ยงสูง

| ฟีเจอร์ | อยู่ที่ไหน | เหตุผล |
|---|---|---|
| ข้ามและซ่อนโฆษณา YouTube | `AutoBridge/YouTube/YouTubeAdSkip.swift`, สวิตช์ `youtube.adSkip` ใน `YouTubeSettings.swift` | ขัดเงื่อนไขการใช้งานของ YouTube ซึ่งผิดกฎ 5.2.2 (บริการของบุคคลที่สาม) และ 5.2.3 (การเข้าถึงสื่อ) เป็นเหตุผลที่ถูกปฏิเสธบ่อยที่สุด **✅ ตัดออกจาก Release แล้ว:** ซ่อนสวิตช์ และไม่ฉีดสคริปต์ (`YouTubeSettings.adSkipAvailable`) |
| รายการ TV สาธารณะที่ติดมากับแอป | `AutoBridge/IPTV/IptvDirectory.swift`: Free-TV และ Thai (dearbulut) ถูกเพิ่มให้อัตโนมัติ (`seeded: true`), iptv-org อยู่ในตัวเลือก | Apple มองว่าแอปพาไปหาสตรีมที่อาจไม่มีลิขสิทธิ์ (5.2.3) แอป IPTV ที่ผ่าน review มักไม่มีเนื้อหาติดมาเลย และให้ผู้ใช้ใส่ playlist เอง **✅ ตัดออกจาก Release แล้ว:** ไม่เพิ่มรายการ TV ให้อัตโนมัติ และไม่มีให้เลือก ปุ่ม Public lists ของ TV ถูกซ่อน ผู้ใช้ยังกด + M3U หรือ + Xtream เพื่อใส่เองได้ ส่วนวิทยุยังมีเหมือนเดิม (`IptvDirectory.offered`) |

### 🟡 เสี่ยงกลาง

| ฟีเจอร์ | อยู่ที่ไหน | เหตุผล |
|---|---|---|
| SponsorBlock (ข้ามช่วงสปอนเซอร์ใน YouTube) | `AutoBridge/YouTube/SponsorBlock.swift`, `SponsorBlockClient.swift` | แก้การเล่นของ YouTube ผ่านการฉีด JavaScript ขึ้นกับว่าใครรีวิว |
| บังคับคุณภาพสูงสุดของ YouTube | `YouTubeEnhancer.swift`, สวิตช์ `youtube.autoHighestQuality` | เหตุผลเดียวกับ SponsorBlock แต่เบากว่า |
| รายการเว็บสตรีมมิง | `AutoBridge/IPTV/StreamingLinks.swift` | เป็นแค่ลิงก์ไปเว็บ แต่ต้องไม่มีเว็บที่ปล่อยเนื้อหาผิดลิขสิทธิ์ |
| เปิด ATS ทั้งหมด | `Info.plist`: `NSAllowsArbitraryLoads` และ `NSAllowsArbitraryLoadsInWebContent` | ต้องเขียนเหตุผลใน review notes ว่าสตรีม IPTV และวิทยุจำนวนมากยังเป็น `http://` ถ้าอธิบายชัดก็ผ่านได้ |

### 🟢 ผ่านได้ตามปกติ

- เครื่องเล่น IPTV ที่ผู้ใช้ใส่ M3U หรือ Xtream ของตัวเอง
- วิทยุจาก radio-browser
- เล่นเสียงในพื้นหลัง (`UIBackgroundModes: audio`) เพราะแอปเล่นเสียงจริง
- เบราว์เซอร์ในแอป, รายการโปรด และประวัติการเล่น
- Picture-in-picture และ AirPlay ผ่าน `VideoPlayer`

### ถ้าจะส่ง App Store

1. ~~ตัดการข้ามโฆษณา YouTube~~ ✅ ทำแล้วใน build Release
2. ~~ไม่เพิ่มรายการ TV สาธารณะให้อัตโนมัติ~~ ✅ ทำแล้วใน build Release
3. ใส่ข้อความในแอปและหน้าร้านว่า "แอปไม่มีเนื้อหาในตัว ผู้ใช้ต้องใช้ playlist ที่ตนมีสิทธิ์"
4. วางตำแหน่งแอปเป็นเครื่องเล่นสื่อหรือวิทยุ ไม่ใช่แอปดูทีวีฟรี

ถ้าทำครบ ประเมินว่าโอกาสผ่านปานกลางถึงดี ถ้าส่งแบบที่เป็นอยู่ตอนนี้ โอกาสผ่านต่ำ

## 4. CarPlay

**ต้องขอสิทธิ์จาก Apple:** สิทธิ์ `com.apple.developer.carplay-audio` และ `com.apple.developer.carplay-video` เป็น managed entitlement ที่ Apple ต้องอนุมัติให้ App ID นี้ก่อน

- [x] **ส่งคำขอ:** ที่ https://developer.apple.com/contact/carplay/ ด้วยบัญชีที่จ่ายเงินแล้ว
- [x] **ได้รับอนุมัติแล้ว:** Apple อนุมัติ **ทั้ง** `com.apple.developer.carplay-audio` และ `com.apple.developer.carplay-video` ให้ App ID `dev.autobridge` (team `V9Q6ZN9L53`) แล้ว
- [x] **ใส่ key กลับในไฟล์ entitlements:** ประกาศทั้งสอง key ใน `AutoBridge/Resources/AutoBridge.entitlements` (เครื่องจริง) และ `AutoBridge/Resources/AutoBridge-Simulator.entitlements` (Simulator) ประกาศทั้งเสียงและวิดีโอเพื่อให้แอปขึ้นหน้า CarPlay home เสมอ (แอปที่มีแต่วิดีโอจะขึ้นเฉพาะรถที่รองรับวิดีโอ)
- [x] **Provisioning profile:** build เครื่องจริงด้วย `-allowProvisioningUpdates` ให้ดึง profile ที่มีทั้งสอง entitlement มาเอง (ยืนยันแล้วว่า profile ที่สร้างใหม่มี carplay-audio และ carplay-video ครบ)

**ทดสอบ:**
- ทดสอบ CarPlay Simulator ใน Xcode ได้ (I/O → External Displays → CarPlay) — ใช้ตั้งค่ารถที่รองรับวิดีโอเพื่อเห็น path วิดีโอทำงาน
- เครื่องทดสอบ iPhone 8 Plus เป็น iOS 16.7 จึงไม่รัน path วิดีโอ (API เป็น iOS 26.4+) ตัว path เสียงเดิมยังทำงานเหมือนเดิมบน iOS 16

**ดูวิดีโอบน CarPlay (ทำแล้ว, modeled on APTV):** iOS 26.4 เปิดให้แอปบุคคลที่สามเล่นวิดีโอบนจอ CarPlay ได้ผ่าน CarPlay framework แอปจึงรองรับแล้ว:
- **ผ่าน template เท่านั้น:** CarPlay ให้แอปใช้ list/thumbnail/details แล้ว **ระบบ** เป็นคนเล่นวิดีโอผ่านกลไกแบบ AirPlay video ไม่ใช่แอปวาดวิดีโอเอง และไม่ใช่เบราว์เซอร์บนจอรถ
- **เช็กการรองรับก่อน:** อ่าน `CPSessionConfiguration.supportsVideoPlayback` ว่ารถรองรับ "video in car" ไหม ถ้ารองรับจึงตั้ง `CPPlaybackConfiguration(preferredPresentation: .video)` ให้แถวที่เป็น TV/วิดีโอ ถ้าไม่รองรับ item จะเล่นเป็นเสียงอย่างเดียว (ดู `CarPlay/CarPlaySceneDelegate.swift`)
- **ระบบล็อกตอนรถวิ่ง:** วิดีโอเล่นได้ตอนจอดเท่านั้น iOS หยุดให้เองเมื่อรถเริ่มวิ่ง แอปไม่ต้องทำอะไรเพิ่ม
- **gate ด้วย `@available`/`#available`:** API ใหม่ทั้งหมด (iOS 26.4+/27) ถูก gate ไว้ บน iOS 16 ไม่มีโค้ดใหม่รัน path เสียงเดิมเหมือนเดิมทุกประการ
- **รถรุ่นเก่าส่วนใหญ่ไม่รองรับ:** รถต้องรองรับ "CarPlay with AirPlay video" จึงจะเห็นวิดีโอ ถ้าไม่รองรับแอปยังขึ้นหน้า CarPlay และเล่นเสียงได้ (เพราะประกาศทั้งสอง entitlement)
- **ที่เห็นคนดูได้ตอนรถวิ่ง:** ยังมาจาก 3 ทางเดิม ซึ่งไม่ได้ทำผ่านแอปใน App Store เลย
  - กล่อง CarPlay AI ที่ข้างในเป็น Android เช่น Ottocast หรือ Carlinkit
  - เครื่องที่ jailbreak แล้วลง tweak เช่น CarBridge
  - จอหลังตลาดที่เปิด AirPlay mirroring ได้

**หมายเหตุเรื่อง App Review ของ video browsing:** path วิดีโอบน CarPlay แสดงเฉพาะ source ที่ผู้ใช้เพิ่ม/จำไว้เอง (จุดยืนเดียวกับ IPTV เดิม — ดูหัวข้อ 3/5) ไม่ได้เพิ่มเนื้อหาติดมากับแอป จึงไม่ต้อง gate ด้วย `BuildFlavor.isStore` เพิ่ม การตัดเนื้อหาใน store build เดิม (รายการ TV, YouTube ad-skip) ยังควบคุมว่ามีเนื้อหาอะไรอยู่ ส่วนจอ CarPlay แค่สะท้อนเนื้อหาที่ build นั้นอนุญาตอยู่แล้ว

**หน้าเว็บบนจอรถ:**
- **จอ AirPlay (ใช้ได้กับเครื่องจริง):** เปิด "หน้าเว็บบนจอรถ (AirPlay)" ในตั้งค่า แล้วต่อจอใหม่ จอรถที่รับ AirPlay (จอหลังตลาด หรือกล่อง CarPlay AI) จะได้เบราว์เซอร์ของตัวเองแทนการสะท้อนจอมือถือ ส่งหน้าเว็บจากเบราว์เซอร์บนมือถือด้วยแถบปุ่มรูปรถ ไม่ต้องขอสิทธิ์เพิ่ม
- **จอ CarPlay (เฉพาะ Simulator):** หน้าเว็บจริงบนจอ CarPlay ต้องใช้สิทธิ์ `com.apple.developer.carplay-maps` ซึ่ง Apple ให้เฉพาะแอปนำทาง จึงใส่ไว้แค่ใน `AutoBridge-Simulator.entitlements` ใน Simulator แอปจะทำงานเป็นแอปนำทาง: หน้าแรกเป็นหน้าเว็บ ลากเพื่อเลื่อน ปุ่มขึ้น/ลง/ย้อน/โหลดใหม่ ปุ่มแว่นขยายพิมพ์ที่อยู่ ปุ่มตารางเปิดหน้า Home เสียงเดิม บนเครื่องจริงไม่มีสิทธิ์นี้ แอปจึงเป็นแอปเสียงเหมือนเดิม

**สิ่งที่ยังไม่เคยทดสอบบนรถจริง:** ปุ่มปิดในหน้า Now Playing จาก PR #25 และ path วิดีโอใหม่ (CarPlay Video) — path วิดีโอยืนยันผ่าน CarPlay Simulator ที่ตั้งค่ารถรองรับวิดีโอ และผ่าน build-time entitlement check เท่านั้น ยังไม่ได้ทดสอบบนรถจริงที่รองรับ AirPlay video (เครื่องทดสอบ iPhone 8 Plus เป็น iOS 16.7 รัน path นี้ไม่ได้)

## 5. เทียบกับ APTV (https://aptv.app/)

APTV เป็นเครื่องเล่น M3U บน App Store ตามหน้าเว็บของเขา (อ่านเมื่อ 2026-10-08):
- **CarPlay:** เล่นวิดีโอบนจอรถได้เฉพาะ iOS 26.4 ขึ้นไป
- **เนื้อหา:** ไม่มีช่องหรือ playlist ติดมากับแอป ผู้ใช้ต้องเพิ่ม source เอง

**ทำไมผ่าน review และนิยม:**
- **ไม่มีเนื้อหาติดมาเลย:** หน้าที่ของแอปคือเครื่องเล่น ไม่ใช่แหล่งช่อง จึงไม่โดนกฎ 5.2.3 ต่างจากเราที่เพิ่ม Free-TV, dearbulut และ iptv-org ให้อัตโนมัติ
- **ไม่ยุ่งกับบริการอื่น:** ไม่มีการข้ามโฆษณา YouTube
- **ได้วิดีโอบน CarPlay ก่อนแอปอื่น:** iOS 26.4 เพิ่มการเล่นวิดีโอบน CarPlay ตอนจอดรถ ผ่านกลไกแบบ AirPlay video
- **ครบทุกอุปกรณ์ Apple:** iPhone, iPad, Apple TV, Mac, Vision Pro และ sync การตั้งค่าผ่าน iCloud

**เงื่อนไขของวิดีโอบน CarPlay (ยืนยันจาก CarPlay framework headers ของ iOS 27 SDK แล้ว):**
- เล่นได้เฉพาะตอนรถจอด และหยุดเองเมื่อรถเริ่มวิ่ง — ระบบจัดการเอง
- รถต้องรองรับ "video in car" ด้วย เช็กผ่าน `CPSessionConfiguration.supportsVideoPlayback` (iOS 26.4+) รถรุ่นเก่าส่วนใหญ่ไม่รองรับ
- entitlement คือ `com.apple.developer.carplay-video` (managed) ซึ่ง Apple อนุมัติให้ `dev.autobridge` แล้ว

**ทำแบบเดียวกับ APTV แล้ว (IMPLEMENTED):**
1. ✅ หัวข้อ 3 ทำแล้ว: build Release ตัดรายการ TV สาธารณะและการข้ามโฆษณา YouTube ออก
2. ✅ ใช้ API วิดีโอบน CarPlay ของ iOS 26.4: `CPSessionConfiguration` (เช็ก `supportsVideoPlayback`), `CPPlaybackConfiguration` (`preferredPresentation = .video/.audio`), `CPListItem.playbackConfiguration` และ `CPNowPlayingTemplate.allowsMiniPlayer` (iOS 27) ทุกตัว gate ด้วย `#available` บน iOS 16 ไม่มีผล
3. ✅ browsing วิดีโอผ่าน CarPlay template แล้ว **ระบบ** เล่นวิดีโอผ่านกลไกแบบ AirPlay video เมื่อรถรองรับและจอดอยู่ ไม่รองรับก็เล่นเสียงอย่างเดียว
4. ⏳ ยืนยันผ่าน CarPlay Simulator (รถรองรับวิดีโอ) + build-time entitlement check แล้ว ยังไม่ได้ทดสอบบนรถจริงที่รองรับ AirPlay video (iPhone 8 Plus iOS 16.7 รันไม่ได้)
