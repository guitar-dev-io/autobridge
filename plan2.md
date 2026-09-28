ช่วยปรับ AutoBridge UI และ Home Dashboard ตาม design ล่าสุด โดยแก้บน project ปัจจุบันเท่านั้น

REF
  6470979a-9b66-4fa6-9352-3d89ce4d7509.png

IMPORTANT:
- ห้ามสร้าง project ใหม่
- ตรวจสอบ implementation เดิมก่อนแก้
- reuse navigation, state, controller และ feature เดิม
- ห้ามทำให้ feature ปัจจุบันเสีย
- ห้ามเพิ่ม app-level restriction ที่ block feature
- หาก Android Auto / OS / vehicle platform บังคับ restriction เอง ให้เคารพ restriction ของ platform
- ห้ามทำ logic เพื่อ bypass safety restriction ของ Android Auto หรือระบบรถ

==================================================
1. HOME DASHBOARD
==================================================

ปรับหน้า AutoBridge Home จาก List Menu เดิม:

Video
Music
Browser

ให้เป็น Dashboard

Header:

[ AutoBridge ]
Your Apps. On The Road.

[ ● Phone Connected ]      [ ⚙ ]

แสดงสถานะ:
- Phone Connected
- Phone Disconnected
- Mirror Ready
- Android Auto Connected

Main cards:

[ 🌐 Browser ]
Open websites, search, and more
>

[ 📱 Mirror ]
Show your phone screen
>

[ ▶ Media ]
Music, Video, and Streaming
>

[ ✦ Agent ]
Ask, search, and control
>

Card layout:
- 2 columns เมื่อพื้นที่เพียงพอ
- responsive
- touch target ใหญ่
- ไม่ hardcode ตาม resolution screenshot

==================================================
2. QUICK LAUNCH
==================================================

เพิ่ม section:

Quick Launch

ตัวอย่าง:

Google
YouTube
Maps
Spotify
Custom shortcut
+

Quick Launch ต้องเป็น dynamic

รองรับ:
- add
- remove
- reorder
- custom URL
- custom action
- launch browser
- launch internal feature

เก็บ configuration แบบ persistent

==================================================
3. RECENT
==================================================

เพิ่ม Recent Activity

ตัวอย่าง:

google.com        2 min ago
YouTube           1 hour ago
Spotify           3 hours ago

Recent รองรับ:

- Browser history ล่าสุด
- Media ล่าสุด
- Mirror ล่าสุด
- Agent action ล่าสุด

จำกัดจำนวน เช่น 5-10 รายการล่าสุด

กดแล้ว resume action นั้นได้ทันที

==================================================
4. BROWSER
==================================================

Browser UI:

[ ← ] [ 🔒 google.com................ ] [ ☆ ] [ ⋮ ]

More Menu:

↻ Reload

⌕ Find in page

✦ Agent

🖥 Desktop Mode
[toggle]

⛶ Fullscreen

↗ Open external browser

⚙ Browser Settings

ห้ามวางชุดปุ่ม overlay แนวตั้งขนาดใหญ่ทับ WebView

WebView ต้องใช้พื้นที่ให้มากที่สุด

==================================================
5. MIRROR
==================================================

หน้า Mirror:

Mirror

Device:
Phone Connected

Status:
Ready to mirror

Primary action:

[ ▶ Start Mirroring ]

Quick options:

[ Fit to screen ]

[ Quality ]

[ Include Audio ]

เพิ่มเติม:

- Fit / Fill
- Orientation
- Resolution
- FPS
- Audio
- latency/status
- reconnect

ถ้ามี mirror implementation เดิม ให้ reuse

==================================================
6. MEDIA
==================================================

รวม Music + Video เดิมเป็น Media

Tabs:

Music | Video | Streaming

Music:

Cover
Title
Artist

Previous
Play/Pause
Next

Progress bar

Video:

รายการ video / source
playback controls

Streaming:

stream URL / supported source ตาม implementation ที่มีอยู่

ห้ามลบ feature Video หรือ Music เดิม
เพียงรวม UI ให้เป็น Media Center

==================================================
7. DRIVING MODE
==================================================

Driving Mode สามารถมีได้

แต่ AutoBridge Driving Mode ต้องเป็น:

"Driving-aware UI Mode"

ไม่ใช่:

"Feature Restriction Mode"

IMPORTANT:

ห้าม AutoBridge block feature เพียงเพราะเข้า Driving Mode

Feature ต่อไปนี้ต้องยังเข้าถึงได้จาก application state เดิม:

- Browser
- Mirror
- Media
- Music
- Video
- Streaming
- Agent
- Quick Launch
- Recent
- Settings
- Desktop Mode
- Fullscreen
- Find in Page
- custom actions

Driving Mode ห้าม:

- disable menu
- hide feature แบบถาวร
- lock Browser
- lock Mirror
- lock Video
- lock Agent
- force redirect ไปหน้าอื่น
- clear WebView
- stop media
- destroy session
- reset current screen

Driving Mode มีหน้าที่เฉพาะปรับ UI เช่น:

- ปุ่มใหญ่ขึ้น
- spacing มากขึ้น
- ลด visual clutter
- แสดง action สำคัญก่อน
- voice action เด่นขึ้น
- status ชัดขึ้น

ตัวอย่างหน้า Driving Mode:

AutoBridge
Driving Mode

[ 🌐 Browser ]

[ 📱 Mirror ]

[ ▶ Media ]

[ ✦ Agent ]

Quick actions:

[ Resume ]
[ Recent ]
[ Voice ]

สามารถกด:

"Show all"

เพื่อแสดง feature ทั้งหมดได้

IMPORTANT:
การสลับ:

Normal Mode -> Driving Mode

หรือ

Driving Mode -> Normal Mode

ต้องไม่ทำให้ current state หาย

เช่น:

Browser เปิด google.com อยู่

เมื่อเข้า Driving Mode

google.com ต้องยังอยู่

เมื่อกลับ Normal Mode

ต้องกลับ state เดิมได้ทันที

เช่นเดียวกับ:

- media playback
- mirror session
- agent context
- fullscreen state
- browser history

==================================================
8. PLATFORM SAFETY
==================================================

แยกให้ชัดระหว่าง:

1. AutoBridge UI mode

กับ

2. Android Auto / OS / Vehicle restrictions

AutoBridge ไม่ต้องเพิ่ม restriction เอง

แต่ห้าม implement code เพื่อ bypass restriction ที่ถูกบังคับโดย:

- Android Auto
- Android OS
- vehicle head unit
- OEM safety system

ถ้า platform API ปฏิเสธ action:

ให้ handle gracefully

เช่น:

Feature unavailable by platform

แต่ห้าม crash application

==================================================
9. AGENT
==================================================

เพิ่ม Agent เป็น feature หลัก

Agent สามารถสั่ง internal action เช่น:

"เปิด Google"

"เปิด Browser"

"เปิด Mirror"

"เปิดเพลงต่อ"

"กลับหน้าล่าสุด"

"เปิดเว็บไซต์ล่าสุด"

"เปิดเต็มหน้าจอ"

"เปิด desktop mode"

สร้าง internal command router เช่น:

AgentAction

OPEN_BROWSER
OPEN_URL
OPEN_MIRROR
OPEN_MEDIA
RESUME_MEDIA
OPEN_RECENT
ENABLE_DESKTOP
DISABLE_DESKTOP
ENTER_FULLSCREEN
EXIT_FULLSCREEN

Agent ต้องเรียก existing navigation/action
ไม่ duplicate business logic

==================================================
10. SESSION / STATE
==================================================

สำคัญมาก:

ทุก feature ต้องเก็บ state แยกกัน

เช่น:

BrowserState
MirrorState
MediaState
AgentState
HomeState

Switch screen แล้วห้าม destroy state โดยไม่จำเป็น

Browser:

- URL
- history
- desktopMode
- loading state
- fullscreen

Media:

- source
- track
- position
- playback state

Mirror:

- connected
- active
- resolution
- orientation

DrivingMode:

- UI presentation only

==================================================
11. AUTO RESUME
==================================================

เพิ่ม optional setting:

Resume last session

เมื่อเปิด AutoBridge:

ถ้าเปิด option นี้

ให้ restore:

- หน้าล่าสุด
- Browser URL ล่าสุด
- Media state
- Mirror readiness
- selected mode

แต่ต้องมี timeout / validation
เพื่อไม่ restore invalid session

==================================================
12. SETTINGS
==================================================

Settings แบ่งเป็น:

General

Connection

Browser

Mirror

Media

Agent

Appearance

Driving Mode

About

Driving Mode Settings:

[✓] Enable driving-aware UI

[✓] Larger controls

[✓] Prefer voice actions

[✓] Reduce visual clutter

IMPORTANT:

ห้ามมี setting เช่น:

Block Browser while driving
Disable Video while driving
Disable Mirror while driving

เพราะ AutoBridge Driving Mode ไม่ใช่ restriction system

==================================================
13. NAVIGATION
==================================================

ตรวจ navigation architecture เดิม

ควรสามารถ navigate ระหว่าง:

HOME
BROWSER
MIRROR
MEDIA
AGENT
SETTINGS

โดย state เดิมไม่ถูก destroy

ใช้ architecture เดิม เช่น:

Navigation Component
Compose Navigation
Fragment
Activity
หรือ routing system ที่ project ใช้อยู่

ห้ามเพิ่ม navigation framework ใหม่ถ้าไม่จำเป็น

==================================================
14. BUILD / TEST
==================================================

หลัง implement ตรวจ:

Home:
- cards ครบ
- responsive
- quick launch ทำงาน
- recent ทำงาน

Browser:
- เปิดเว็บ
- back
- reload
- menu
- desktop mode
- fullscreen
- find in page

Mirror:
- start/stop
- state ไม่หาย

Media:
- music
- video
- streaming
- playback state

Agent:
- internal navigation
- command execution

Driving Mode:
- เปิด/ปิดได้
- ทุก feature ของ AutoBridge ยังสามารถเข้าถึงได้
- ไม่มี app-level block
- state ไม่หายเมื่อเปลี่ยน mode
- UI เปลี่ยนอย่างเดียวเป็นหลัก

Android Auto:
- DHU เปิดได้
- ไม่มี overflow
- landscape ถูกต้อง
- different resolutions ถูกต้อง

Platform:
- ถ้า platform ปฏิเสธ action ต้อง handle gracefully
- ห้าม implement bypass ของ platform safety restriction

==================================================
ก่อนแก้ CODE
==================================================

STEP 1:
Inspect project

STEP 2:
รายงานว่าแต่ละ feature อยู่ไฟล์ไหน:

Home
Browser
Mirror
Video
Music
Agent
Settings
Android Auto
Driving Mode
State management

STEP 3:
บอก feature ที่มีอยู่แล้ว

STEP 4:
บอก feature ที่ยังไม่มี

STEP 5:
สร้าง implementation plan แบบ minimal changes

STEP 6:
ค่อยเริ่มแก้ code

ห้าม rewrite working implementation โดยไม่มีเหตุผล