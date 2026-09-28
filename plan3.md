ช่วยตรวจสอบ project ปัจจุบันก่อน แล้วปรับ UI หน้าแรกของ AutoBridge / Android Auto Head Unit


#Ref
  /Users/anuwat.t/Documents/ChatGPT/AutoBridge/ChatGPT Image 15 ก.ย. 2569 19_40_55.png
  
IMPORTANT:
- ห้ามสร้าง project ใหม่
- ห้าม rewrite architecture เดิมทั้งหมด
- ให้แก้จาก code ปัจจุบัน
- ห้ามลบ function ที่มีอยู่
- Back / Home / Recents / Stop และคำสั่งเดิมต้องยังใช้งานได้
- ต้องไม่ block feature อื่นของระบบ
- UI ต้อง responsive รองรับ Android Auto screen หลาย resolution / aspect ratio
- ให้แยก UI กับ logic ออกจากกัน
- ถ้ามี component เดิมที่ reuse ได้ ให้ reuse
- ก่อนแก้ให้ตรวจสอบ flow และไฟล์ที่เกี่ยวข้องทั้งหมดก่อน

เป้าหมาย:
เมื่อเปิด AutoBridge ครั้งแรก ปัจจุบันจะเจอหน้าจอดำและมีปุ่ม

Back
Home
Recents
Stop

อยู่ด้านบน

ให้เปลี่ยน First Launch UI เป็น Dashboard แบบใหม่


==================================================
1. FIRST LAUNCH DASHBOARD
==================================================

สร้างหน้า Dashboard ภายในพื้นที่ content ของ Android Auto

โครงสร้าง:

Top Header

[ AutoBridge Logo ] AutoBridge                 [ USB Connected ]

- พื้นหลังดำ / dark theme
- Header ไม่สูงเกินไป
- Connection Status อยู่ด้านขวา

Status รองรับ:

USB Connected
Wireless Connected
Connecting...
Disconnected

ใช้สี:
Connected = green
Connecting = amber
Disconnected = gray/red ตามความเหมาะสม


==================================================
2. WELCOME AREA
==================================================

ตรงกลางหน้าแสดง

AutoBridge Logo

Welcome to AutoBridge

Start your first session by choosing a mode below.

ไม่ต้องแสดงทุกครั้ง

ให้แสดง welcome message เฉพาะ:
- first launch
หรือ
- ไม่มี active session
หรือ
- ยังไม่เคยใช้งาน feature ใด


==================================================
3. QUICK ACTIONS
==================================================

สร้าง card 4 ตัว

Browser
Mirror
Apps
Settings

Layout:

┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│    🌐    │ │    ▣     │ │    ▦     │ │    ⚙    │
│ Browser  │ │ Mirror   │ │ Apps     │ │ Settings │
│          │ │          │ │          │ │          │
└──────────┘ └──────────┘ └──────────┘ └──────────┘


Browser
description:
Browse the web

action:
เปิด Browser Mode


Mirror
description:
Show phone screen

action:
เปิด Screen Mirror Mode


Apps
description:
Open Android apps

action:
เปิด App Launcher


Settings
description:
Configure AutoBridge

action:
เปิด Settings


IMPORTANT:
ห้ามทำเป็น mock button

ทุก card ต้องเชื่อมกับ function จริงที่มีอยู่ใน project

ถ้ายังไม่มี function ของบาง feature:
- เตรียม callback/interface ไว้
- TODO อธิบายชัดเจน
- ห้าม hardcode fake action


==================================================
4. REMOVE LARGE DEBUG CONTROLS
==================================================

ปัจจุบันมี

Back
Home
Recents
Stop

เป็นปุ่มใหญ่ด้านบน

ให้เอาออกจาก main UI

แต่ห้ามลบ functionality

ให้ย้ายไปอยู่ใน Control Menu


==================================================
5. FLOATING CONTROL MENU
==================================================

ด้านขวาของหน้าจอให้มี floating button

≡

กดแล้วเปิด Quick Control Panel

ภายในมี:

← Back
⌂ Home
▣ Recents
⌨ Keyboard
↻ Reload
⛶ Fullscreen
🔒 Lock Controls
■ Stop Session


ต้องเรียก command/function เดิมของระบบ

ตัวอย่าง:

Back
→ existing back command

Home
→ existing home command

Recents
→ existing recent apps command

Stop Session
→ existing stop/session terminate command


Stop Session ให้ใช้ danger state
และอย่าวางเป็นปุ่มใหญ่บน main screen


==================================================
6. RECENT SESSION
==================================================

ด้านล่าง Quick Actions เพิ่ม

Recent

ถ้ายังไม่มี history:

Recent
No recent sessions yet

ถ้ามี:

Recent
Browser
youtube.com

[ Resume ]

เก็บโครงสร้างรองรับ:

type
title
url/package
lastOpenedAt
mode

ไม่ต้องสร้าง database ใหม่ถ้า project มี storage อยู่แล้ว

ให้ตรวจสอบระบบเดิมก่อน


==================================================
7. SESSION BEHAVIOR
==================================================

กำหนด behavior ดังนี้

FIRST RUN:

Launch
↓
AutoBridge Dashboard
↓
เลือก Browser / Mirror / Apps


SUBSEQUENT RUN:

ถ้ามี setting:

Resume Last Session = ON

ให้:

Launch
↓
ตรวจสอบ previous session
↓
resume ได้ → เปิด session เดิม
resume ไม่ได้ → Dashboard


ถ้า Resume Last Session = OFF

Launch
↓
Dashboard


==================================================
8. ANDROID AUTO BOTTOM BAR
==================================================

ห้ามสร้าง bottom bar ปลอมมาทับ Android Auto

พื้นที่ navigation / system bar ด้านล่างที่ Android Auto มีอยู่แล้ว
ให้คงไว้

Dashboard ต้อง render เฉพาะพื้นที่ app content

ต้อง respect:

safe area
system inset
navigation area


==================================================
9. RESPONSIVE
==================================================

ห้าม fix size ตาม Desktop Head Unit screenshot

ต้องรองรับเช่น:

800x480
1024x600
1280x720
1920x720
wide screen
portrait-like emulator/debug screen

ถ้าพื้นที่ไม่พอ:

4 cards
→ 2x2

ถ้าหน้าจอกว้าง:

→ 4 cards row


ห้ามเกิด:

overflow
text clipped
button ทับกัน
scroll แนวนอนโดยไม่จำเป็น


==================================================
10. DESIGN STYLE
==================================================

ใช้ Dark UI

Background:
#000000 / #0B0D10

Card:
#171A20 หรือใกล้เคียง

Border:
white opacity 8-12%

Primary:
#4285F4 หรือ AutoBridge primary color

Connected:
#34A853

Text Primary:
#FFFFFF

Text Secondary:
#AEB4C0


Card:

borderRadius 20-24
minimal shadow
touch target ใหญ่
ใช้ icon ขนาดใหญ่พอมองเห็นในรถ

ไม่ต้องใส่ animation เยอะ

ใช้ animation เฉพาะ:
hover/focus
pressed
menu open
connection state


==================================================
11. DRIVING / TOUCH UX
==================================================

UI ต้อง touch friendly

minimum target ประมาณ 48dp ขึ้นไป

หลีกเลี่ยง:
- ตัวหนังสือเล็ก
- เมนูซับซ้อน
- button ชิดกัน
- action สำคัญซ่อนหลายชั้นเกินไป


==================================================
12. STATE MODEL
==================================================

ควรมี state กลางประมาณ:

AutoBridgeUiState

connectionState
activeMode
hasActiveSession
isFirstLaunch
recentSessions
controlMenuOpen
controlLocked


ActiveMode:

none
browser
mirror
apps


ConnectionState:

disconnected
connecting
usb
wireless


==================================================
13. IMPORTANT ARCHITECTURE
==================================================

ห้ามผูก UI โดยตรงกับ adb/shell/AA command

ใช้ประมาณ:

UI
↓
Controller / ViewModel
↓
AutoBridgeService
↓
ADB / Android Auto / Head Unit command


ตัวอย่าง:

onHomePressed()
onBackPressed()
onRecentsPressed()
onStartBrowser()
onStartMirror()
onOpenApps()
onStopSession()


เพื่อให้ UI ไม่รู้ implementation ด้านล่าง


==================================================
14. FIRST STEP
==================================================

ก่อน implement:

1. หา entry point ของหน้าปัจจุบัน
2. หา code ของ Back/Home/Recents/Stop
3. หา session management
4. หา Android Auto / DHU communication
5. หา browser/mirror/app launcher implementation
6. หา persistence/settings ปัจจุบัน
7. สรุป architecture ปัจจุบันให้ฉันดูก่อน


จากนั้นค่อย implement


==================================================
15. RESULT
==================================================

หลังทำเสร็จให้รายงาน:

Files changed

Files created

Existing functions reused

New functions added

Flow ก่อนแก้

Flow หลังแก้

Feature ไหนยัง TODO

และตรวจสอบว่า:

Back ใช้งานได้
Home ใช้งานได้
Recents ใช้งานได้
Stop ใช้งานได้
Browser เปิดได้
Mirror เปิดได้
Apps เปิดได้
Settings เปิดได้
Control Menu เปิด/ปิดได้
layout ไม่ overflow


อย่าเปลี่ยนส่วนอื่นของ project ที่ไม่เกี่ยวข้อง