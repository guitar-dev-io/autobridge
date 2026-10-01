# แผน: วิดีโอ 65% + แผนที่ 35% ในหน้าต่างเดียว (internal split)

สถานะ: ร่างแผน ยังไม่ลงมืองาน #2
อัปเดต: 2026-10-02

เอกสารนี้เป็นแผนสำหรับงาน #2 ("ย้ายวิดีโอ ExoPlayer เข้าไปอยู่ร่วมหน้าต่าง Presentation กับแผนที่")
เขียนไว้ให้ตัดสินใจและลงมือทีหลังได้โดยไม่ต้องไล่โค้ดใหม่ อ้างอิงจากซอร์สจริง

---

## 1. เป้าหมาย

```
+----------------------------+----------------+
|                            |                |
|  วิดีโอ / เว็บ  (main 65%)   |  แผนที่ (side   |
|                            |  35%)          |
|                            |                |
+----------------------------+----------------+
```

ช่องซ้าย/ขวาสลับได้ด้วย `BrowserSplitStore.sideOnRight` (มีอยู่แล้ว)
เพรเซ็ต 65/35 = `BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE` (เพิ่มแล้วในงาน #1)

---

## 2. สองทางเลือกของ "ช่องวิดีโอ"

### ทาง (ก) — ช่องหลักเป็นวิดีโอเว็บ (WebView เล่น `<video>`)

**สถานะ: เสร็จแล้วโดยปริยายจากงาน #1** ไม่ต้องเขียนโค้ดเพิ่ม

เหตุผล: ใน HARDWARE mode ทั้ง main pane และ side pane เป็น `WebView` ที่โฮสต์อยู่ใน
`CarHardwareWebWindow.contentLayer` (หน้าต่าง Presentation เดียว) อยู่แล้ว
- main pane = เบราว์เซอร์เต็ม (เปิด YouTube/เว็บวิดีโอได้)
- side pane = เว็บหน้าเดียว (default `https://www.google.com/maps`)

วิธีใช้งานจริง:
1. เปิด split layout เป็น "แบ่ง 65/35" (drawer → cycleSplitLayout หรือ Settings → Split screen)
2. main เปิด YouTube, side เป็น Maps เว็บ (ค่า default)

ข้อจำกัด (แพลตฟอร์ม/ของเดิม ไม่ใช่บั๊ก):
- วิดีโอ DRM (เช่น Netflix) ใช้ได้เฉพาะ HARDWARE mode เท่านั้น (LEGACY วาดไม่ได้)
- แผนที่เป็น Google Maps "เว็บ" ไม่ใช่แอป Maps/Waze จริง

**ถ้าเป้าหมายคือดู YouTube/เว็บข้างแผนที่ → จบที่ทางนี้ ไม่ต้องทำ #2**

### ทาง (ข) — ช่องหลักเป็นวิดีโอคลัง/IPTV (ExoPlayer จริง)

งานใหญ่ ต้องย้ายการวาดวิดีโอ ExoPlayer เข้าไปในหน้าต่าง Presentation เดียวกับแผนที่
รายละเอียดอยู่หัวข้อ 4

---

## 3. สถาปัตยกรรมปัจจุบัน (ข้อเท็จจริงจากซอร์ส)

### เส้นทางเว็บ (browser)
```
CarBrowserScreen (SurfaceCallback)
  → MirrorSurfaceOwnership.claim(this) + appManager.setSurfaceCallback(this)
  → CarWebRenderer.start(surface, w, h, dpi)
     → attachHardwareWindow(): CarHardwareWebWindow.create(...)
        VirtualDisplay(OWN_CONTENT_ONLY|PRESENTATION, sink = car surface)
          → Presentation (HW accelerated)
             ├─ contentLayer (FrameLayout)   ← main WebView (+ side WebView ตอน split)
             └─ chromeLayer (วาด toolbar/drawer/FAB)
```

### เส้นทางวิดีโอ native (คลัง/IPTV)
```
CarVideoScreen (SurfaceCallback)
  → MirrorSurfaceOwnership.claim(this) + appManager.setSurfaceCallback(this)
  → MediaPlaybackClient (ถือ MediaController เท่านั้น ไม่ใช่ ExoPlayer)
  → onSurfaceAvailable: player.setVideoSurface(host surface ตรง ๆ)
  → VideoOutputGeometry.set(w,h)
       → MediaPlaybackService.geometryListener → applyOutputGeometry()
          (ExoPlayer จริงอยู่ที่นี่: setVideoEffects(Presentation letterbox)
           + Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
```

### ข้อจำกัดสำคัญที่ทำให้ (ข) เป็นงานใหญ่
1. **surface เดียว แย่งกันด้วย `MirrorSurfaceOwnership`** (identity-based: claim/isOwner/release)
   `CarVideoScreen` และ `CarBrowserScreen` คนละ Screen ผลัดกันเป็นเจ้าของ → อยู่ร่วม template
   เดียวกันไม่ได้ ต้องรวมเป็น Screen เดียว + หน้าต่าง Presentation เดียว
2. **`CarWebRenderer.hardwareWindow` เป็น private ไม่มี accessor** เอา `contentLayer` ออกมาจาก
   นอกไม่ได้ ต้องเพิ่ม API ภายใน renderer
3. **ExoPlayer จริงเข้าถึงจากฝั่งจอไม่ได้** จอถือแค่ `MediaController` (`MediaPlaybackClient.player`)
   แต่ `MediaController.setVideoSurface(Surface)` ใช้ได้ (ส่ง surface ข้ามไป service) จึงป้อน
   surface ของ SurfaceView ให้ player ได้ แม้ `setVideoSurfaceView()`/effects จะเรียกตรงไม่ได้
4. **ไม่มี `SurfaceView`/`PlayerView` ในเส้นทาง car เลย** (มีแค่ใน PlayerActivity/EntertainmentActivity
   ฝั่งโทรศัพท์) ฝั่ง car จงใจเป็น bare-Surface pipeline

---

## 4. แผนลงมือสำหรับทาง (ข)

### แนวคิด
รวมวิดีโอ + แผนที่ไว้ใน **หน้าต่าง Presentation เดียว** โดย:
- main pane 65% = `SurfaceView` ที่ ExoPlayer วาดลง (ป้อน surface ผ่าน MediaController)
- side pane 35% = WebView แผนที่ (ใช้ side pane ที่มีอยู่แล้วใน CarWebRenderer)

เลือกใช้โครง `CarWebRenderer` + `CarHardwareWebWindow` ที่มีอยู่เป็นฐาน แทนที่จะสร้าง Screen ใหม่
เพราะ side pane (แผนที่) + หน้าต่าง Presentation + การจัด geometry มีครบแล้ว

### ขั้นตอน

**4.1 เพิ่ม video layer ใน `CarHardwareWebWindow`**
ไฟล์: `browser/CarHardwareWebWindow.kt`
- เพิ่มเมธอด `placeVideoSurfaceView(view: SurfaceView, left, top, w, h)` วาง SurfaceView ใน
  `contentLayer` (คล้าย `placePage` แต่สำหรับ SurfaceView) ให้อยู่ลึกกว่า WebView แผนที่
- หรือเปิด getter `contentLayer` ให้ renderer ใช้ (public อยู่แล้วในคลาส แต่เข้าถึงจากนอกไม่ได้
  เพราะ instance เป็น private ใน renderer)
- ระวัง: SurfaceView บน VirtualDisplay/Presentation ต้องเป็น hardware accelerated (addFlags
  FLAG_HARDWARE_ACCELERATED มีแล้วตอน create) และ z-order ของ SurfaceView อาจทับ chromeLayer
  → อาจต้องใช้ `setZOrderMediaOverlay(true)` หรือพิจารณา TextureView แทนถ้า z-order มีปัญหา

**4.2 เพิ่มโหมด "วิดีโอ+แผนที่" ใน `CarWebRenderer`**
ไฟล์: `browser/CarWebRenderer.kt`
- เพิ่ม state ใหม่: `videoSurfaceView: SurfaceView?` + callback เมื่อ surface ของมันพร้อม
- ใน `layoutWebView()` ตอนคำนวณ panes: ถ้าโหมดเป็น "วิดีโอ+แผนที่" ให้ main pane วาง
  `videoSurfaceView` แทน main WebView (ใช้ `BrowserSplitGeometry.panes` ตัวเดิม)
- เพิ่ม public API: `startVideoMap(uri, title)` / `stopVideoMap()` ให้ Screen เรียก
- side pane (แผนที่) ใช้โค้ด `placeSidePane()` เดิม

**4.3 ป้อน surface ของ SurfaceView ให้ player**
ไฟล์: `media/MediaPlaybackClient.kt` (หรือใน Screen)
- `SurfaceHolder.Callback.surfaceCreated` → `media.player?.setVideoSurface(holder.surface)`
- `surfaceDestroyed` → `clearVideoSurface`
- ยังต้องตั้ง `VideoOutputGeometry.set(paneW, paneH)` ด้วย **ขนาดของ pane 65%** (ไม่ใช่ทั้งจอ)
  เพื่อให้ letterbox ถูก — จุดนี้ต่างจากของเดิมที่ใช้ทั้ง surface
- ถ้าใช้ SurfaceView จริง ๆ อาจไม่ต้องพึ่ง VideoOutputGeometry เพราะ setVideoSurface กับ view ที่
  มีขนาดอยู่แล้วจะ letterbox ให้ แต่ MediaController เรียก setVideoSurfaceView ไม่ได้ จึงยังต้อง
  ส่งขนาด pane ผ่าน VideoOutputGeometry เหมือนเดิม

**4.4 สร้าง Screen เดียวที่คุมทั้งคู่**
- ทางเลือก A: ให้ `CarBrowserScreen` รองรับโหมดวิดีโอ+แผนที่ (main=video) — รวมใน Screen เดียว
  ไม่ต้องแย่ง MirrorSurfaceOwnership
- ทางเลือก B: สร้าง `CarVideoMapScreen` ใหม่ที่ถือ renderer + MediaPlaybackClient
- แนะนำ A เพราะ renderer เป็น session-scoped (CarBrowserRuntime) อยู่แล้ว

**4.5 จัดการ lifecycle / regression ที่ต้องระวัง (จาก comment ในโค้ด)**
- audio focus: `CarWebRenderer.start/stop` มีตรรกะ "ไม่ปล่อย focus ถ้ายังเล่นอยู่" ต้องรวม
  สถานะ ExoPlayer เข้าไปด้วย ไม่งั้นวิดีโอ pause ตอนสลับแอป
- surface churn: host ยึด surface ชั่วคราว → `setSurface(null)` แล้วคืน ต้อง resume ทั้งวิดีโอ
  และแผนที่ (ของเดิมมี `resumeWhenSurfaceReturns` ใน CarVideoScreen ต้องยกตรรกะมา)
- live stream rejoin: BEHIND_LIVE_WINDOW → seekToDefaultPosition (ของเดิมมีใน CarVideoScreen +
  MediaPlaybackService.playerListener)
- parking gate: `allowed()` (SafetyEnforcement.gateParked + FeaturePolicy VIDEO) ต้องคงไว้

### ไฟล์ที่ต้องแก้ (ทาง ข)
1. `browser/CarHardwareWebWindow.kt` — เพิ่ม placeVideoSurfaceView / เปิดทางวาง SurfaceView
2. `browser/CarWebRenderer.kt` — โหมดวิดีโอ+แผนที่, วาง SurfaceView ใน main pane, API start/stop
3. `car/CarBrowserScreen.kt` (หรือ `CarVideoMapScreen.kt` ใหม่) — คุม lifecycle + MediaPlaybackClient
4. `media/MediaPlaybackClient.kt` — helper ป้อน surface ของ SurfaceView ให้ player (ถ้าจำเป็น)
5. `media/VideoOutputGeometry.kt` / `MediaPlaybackService.kt` — รองรับขนาด pane (ไม่ใช่ทั้งจอ)
6. `car/CarVideoLauncher.kt` — เพิ่มทางเปิดโหมดวิดีโอ+แผนที่ (ผ่าน disclaimer เดิม)
7. เทสต์: geometry มีแล้ว; เพิ่มเทสต์ logic การเลือก pane/VideoOutputGeometry ถ้าแยก logic ออกมาได้

### ความเสี่ยง / สิ่งที่ต้องยืนยันบนฮาร์ดแวร์จริง
- SurfaceView z-order บน Presentation/VirtualDisplay (อาจเห็นดำทับ chrome หรือแผนที่)
- ประสิทธิภาพ: วิดีโอ decode + WebView แผนที่ พร้อมกันบน VirtualDisplay เดียว
- DHU 2.0 ต่อ AA รุ่นใหม่ไม่ได้ (ดู docs/DHU.md) → ต้องเทสต์ head unit จริง
- การแย่ง audio focus ระหว่าง ExoPlayer กับ WebView แผนที่ (ถ้าแผนที่ไม่มีเสียงก็ไม่เป็นไร)

---

## 5. ที่รันได้ (ทั้ง ก และ ข — เป็น layout ภายในแอป ไม่เกี่ยว Coolwalk ของ host)

| สภาพแวดล้อม | ได้ไหม | หมายเหตุ |
|---|---|---|
| โทรศัพท์อย่างเดียว | ได้ | |
| DHU | ตามหลักการได้ | DHU 2.0 ต่อ AA ใหม่ไม่ติด ต้อง head unit จริง (docs/DHU.md) |
| head unit จริง | ได้ (เป้าหมาย) | ยังไม่ยืนยันครบ (ROADMAP) ต้อง HARDWARE render mode |

ต้อง sideload (flavor personal/lab) ถ้าจะให้โชว์เป็นแอป split ข้าง Maps จริงของ host
แต่ internal split (ก/ข) นี้ไม่ต้องพึ่ง host split — ทำงานในพื้นที่ของแอปเองบนเส้นทางเทมเพลต

---

## 6. ข้อเสนอ

- ถ้าต้องการแค่ "ดู YouTube/เว็บ ข้างแผนที่" → ใช้ทาง (ก) ที่เสร็จแล้ว ไม่ต้องทำ #2
- ถ้าต้องการ IPTV/ไฟล์วิดีโอในคลัง ข้างแผนที่ → ลงมือทาง (ข) ตามขั้น 4.1–4.5
  แนะนำทำทีละขั้น + เทสต์ geometry/logic บน JVM ก่อน แล้วค่อยยืนยันภาพจริงบน head unit
