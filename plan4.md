ช่วยเพิ่มระบบ Mobile Remote & Command ให้ AutoBridge บน project ปัจจุบัน


#ref
  /Users/anuwat.t/Documents/ChatGPT/AutoBridge/ChatGPT Image 15 ก.ย. 2569 19_41_15.png

IMPORTANT:
- ห้ามสร้าง project ใหม่
- ห้าม rewrite architecture เดิมถ้าไม่จำเป็น
- ตรวจสอบระบบ Android Auto, Browser, Mirror, Media, Agent, navigation และ state management เดิมก่อน
- reuse controller / service / command / navigation เดิมให้มากที่สุด
- Mobile App และ Android Auto ต้อง share command/state เดียวกัน
- ห้าม duplicate business logic
- ต้องรองรับ real-time command และ real-time state sync
- ห้าม block feature ของ AutoBridge เพิ่มเอง
- หาก Android Auto / OS / Head Unit ปฏิเสธ action ให้ handle gracefully
- ห้าม implement bypass ของ platform safety restriction

==================================================
1. MOBILE REMOTE SCREEN
==================================================

เพิ่มหน้า:

AutoBridge Remote

Header:

AutoBridge
Your Car. Smarter.

Status:

● Connected to Car
Android Auto Active

หรือ:

● Phone connected
● Android Auto connected
● Mirror active

ถ้าไม่เชื่อม:

○ Android Auto not connected

ต้อง update แบบ real-time

==================================================
2. COMMAND INPUT
==================================================

เพิ่มช่อง command หลัก:

[ พิมพ์คำสั่งหรือข้อความ...              ] [ Send ]

ตัวอย่าง:

เปิด google.com
เปิด Browser
เปิด YouTube
เปิด Mirror
เปิด Spotify
เปิดเต็มหน้าจอ
รีเฟรช
เปิด Desktop Mode
กลับหน้าก่อน
เล่นเพลงต่อ
หยุดเพลง

รองรับทั้ง:

1. Structured commands
2. Natural language commands

เมื่อกด Send:

Mobile
   ↓
Command Router
   ↓
AutoBridge Controller
   ↓
Android Auto Screen

ต้อง execute โดยไม่ต้องเปิดหน้าใหม่บนมือถือ

==================================================
3. COMMAND MODEL
==================================================

สร้าง command model กลาง เช่น:

AutoBridgeCommand

fields:

id
type
payload
source
timestamp
status

source:

MOBILE
ANDROID_AUTO
AGENT
SYSTEM

status:

PENDING
EXECUTING
SUCCESS
FAILED

ตัวอย่าง command types:

OPEN_BROWSER
OPEN_URL
SEARCH_WEB
GO_BACK
GO_FORWARD
RELOAD

OPEN_MIRROR
START_MIRROR
STOP_MIRROR

OPEN_MEDIA
PLAY
PAUSE
NEXT
PREVIOUS

OPEN_AGENT

ENTER_FULLSCREEN
EXIT_FULLSCREEN

ENABLE_DESKTOP_MODE
DISABLE_DESKTOP_MODE

OPEN_HOME
OPEN_SETTINGS

SEND_TEXT_TO_SCREEN
FOCUS_INPUT

==================================================
4. COMMAND ROUTER
==================================================

สร้าง central:

AutoBridgeCommandRouter

หน้าที่:

รับ command
↓
validate
↓
resolve target
↓
เรียก existing feature/controller
↓
return result

ตัวอย่าง:

OPEN_URL
→ BrowserController.openUrl()

OPEN_MIRROR
→ navigation.openMirror()

RELOAD
→ BrowserController.reload()

ENTER_FULLSCREEN
→ BrowserController.setFullscreen(true)

PLAY
→ MediaController.play()

IMPORTANT:

ห้ามเขียน logic Browser / Mirror / Media ซ้ำใน Command Router

Router ต้องเรียก implementation เดิมเท่านั้น

==================================================
5. NATURAL LANGUAGE
==================================================

เพิ่ม parser layer:

CommandParser

ตัวอย่าง:

"เปิด google.com"

→

OPEN_URL
url = https://google.com

"เปิด youtube"

→

OPEN_URL / app action ตาม implementation ที่มี

"ค้นหา ร้านกาแฟใกล้ฉัน"

→

SEARCH_WEB
query = ร้านกาแฟใกล้ฉัน

"เปิดเต็มหน้าจอ"

→ ENTER_FULLSCREEN

"เปิด desktop mode"

→ ENABLE_DESKTOP_MODE

"กลับ"

→ GO_BACK

ถ้า parse ไม่ได้:

ส่งต่อให้ Agent ได้

Flow:

Text
 ↓
Local Command Parser
 ↓
ถ้ารู้จัก command
   → execute
ถ้าไม่รู้จัก
   → Agent

==================================================
6. QUICK COMMANDS
==================================================

บนหน้า Mobile Remote เพิ่ม:

คำสั่งด่วน

Cards:

🌐 Browser
▶ YouTube
🗺 Maps
🎵 Spotify

📱 Mirror
⛶ Fullscreen
↻ Reload
✦ Agent

Quick command ต้องใช้ Command Router เดียวกัน

ห้ามทำ navigation/action แยกอีกชุด

Quick Commands ต้องสามารถ:

- add
- remove
- reorder
- customize

เก็บ persistent configuration

==================================================
7. CURRENT SCREEN / LIVE STATUS
==================================================

แสดง:

สถานะปัจจุบัน

เช่น:

Browser
google.com

หรือ:

Mirror
Active

หรือ:

Media
Spotify
Playing

หรือ:

Agent
Ready

Mobile ต้องรับ state จาก AutoBridge แบบ real-time

ตัวอย่าง state:

AutoBridgeState

currentScreen
currentUrl
browserTitle
browserLoading

mirrorStatus

mediaTitle
mediaPlaying
mediaPosition

agentStatus

androidAutoConnected

==================================================
8. TWO-WAY SYNC
==================================================

ต้องเป็น Two-Way Communication

Mobile → Android Auto

และ

Android Auto → Mobile

ตัวอย่าง:

มือถือส่ง:

OPEN_URL google.com

Android Auto:
เปิด google.com

จากนั้น Android Auto ส่ง state:

currentScreen = BROWSER
currentUrl = google.com

มือถือ update:

✓ เปิด google.com แล้ว

Browser
google.com

--------------------------------

ถ้ากด Mirror จาก Android Auto

มือถือ update ทันที:

Current Screen:
Mirror

Status:
Active

==================================================
9. COMMAND RESULT
==================================================

ทุก command ต้องมี acknowledgement

ตัวอย่าง:

Command sent
↓
Executing
↓
Success

บนมือถือ:

✓ คำสั่งสำเร็จ

เปิด google.com แล้ว

หรือ:

✕ ไม่สามารถทำคำสั่งได้

เหตุผล:
Android Auto not connected

หรือ:

Action unavailable by platform

เก็บ command history ล่าสุดประมาณ 20 รายการ

==================================================
10. SEND TEXT TO SCREEN
==================================================

เพิ่ม feature:

Send Text to Screen

UI:

ส่งข้อความไปยังหน้าจอรถ

[ ภูสอยดาว                   ] [ Send ]

ตัวเลือก:

○ Search field
○ Focused input
○ Browser address/search
○ Agent

กรณี Browser:

ถ้า WebView มี input focus อยู่
ให้พยายามส่งข้อความเข้า focused field

ถ้าไม่มี focused field:

fallback:

Browser search/address input

ตัวอย่าง:

มือถือ:

"ภูสอยดาว"

Android Auto Browser:

Google Search:
ภูสอยดาว

IMPORTANT:

สร้าง abstraction เช่น:

TextInjectionController

methods:

sendToFocusedInput(text)
sendToBrowserSearch(text)
sendToAgent(text)

ถ้า WebView implementation รองรับ native input API ให้ใช้ก่อน

หลีกเลี่ยง JavaScript injection ถ้าไม่จำเป็น

ถ้าจำเป็นต้องใช้ JS:
จำกัดเฉพาะ WebView ที่ AutoBridge ควบคุมเอง
validate และ escape text ให้ถูกต้อง

==================================================
11. OPTIONAL AUTO SUBMIT
==================================================

เพิ่ม option:

[ ] ส่งแล้วกด Enter อัตโนมัติ

ตัวอย่าง:

text:
ภูสอยดาว

Auto submit:

true

→ Google search ทำงานทันที

default:

OFF

==================================================
12. AGENT COMMAND
==================================================

หน้า Mobile Remote สามารถใช้ Agent ได้

ตัวอย่าง:

User:

"หาร้านกาแฟใกล้ฉัน"

Agent response:

กำลังค้นหาร้านกาแฟใกล้คุณ...

Actions:

OPEN_MAPS
SEARCH "ร้านกาแฟใกล้ฉัน"
SHOW_RESULT_ON_CAR

หรือ:

"เปิดเว็บล่าสุด"

Agent:

OPEN_RECENT_BROWSER

Agent ห้าม control UI โดยตรง

ต้องสร้าง AgentAction
แล้วส่งผ่าน Command Router

==================================================
13. COMMAND HISTORY
==================================================

เพิ่มหน้า:

Recent Commands

ตัวอย่าง:

✓ เปิด google.com
19:26

✓ Reload
19:25

✓ Fullscreen
19:24

กดรายการเพื่อ:

Run again

รองรับ:

clear history

==================================================
14. CONNECTION ARCHITECTURE
==================================================

ตรวจ architecture project ก่อน

ถ้า Mobile UI และ Android Auto อยู่ process/app เดียวกัน:

prefer:

StateFlow
SharedFlow
ViewModel
Repository
Service
Broadcast / Binder ตาม architecture ที่เหมาะสม

สร้างเป็น:

Mobile UI
    ↓
CommandRepository
    ↓
CommandBus
    ↓
CommandRouter
    ↓
Feature Controllers

และ:

Feature State
    ↓
StateRepository
    ↓
Mobile UI
    ↓
Android Auto UI

อย่าสร้าง WebSocket / Backend server ถ้าไม่จำเป็น

==================================================
15. COMMAND BUS
==================================================

สร้าง centralized CommandBus

ตัวอย่าง concept:

send(command)

observeCommands()

observeResults()

observeState()

CommandBus ต้อง:

- thread safe
- lifecycle safe
- handle reconnect
- ป้องกัน command duplicate
- รองรับ command id
- รองรับ acknowledgement

==================================================
16. DUPLICATE PROTECTION
==================================================

ทุก command มี unique id

ถ้า command เดิมถูก execute แล้ว:

ห้าม execute ซ้ำ

เก็บ recent command ids ชั่วคราว

เพื่อป้องกัน:

double tap
reconnect
re-subscribe
rotation
configuration change

==================================================
17. RECONNECT
==================================================

ถ้า Android Auto disconnected:

Mobile Remote ต้องยังเปิดได้

แต่แสดง:

Android Auto disconnected

ถ้าผู้ใช้ส่ง command:

เก็บเป็น:

FAILED
reason = NOT_CONNECTED

ห้าม crash

เมื่อ Android Auto reconnect:

sync current state ใหม่

แต่ห้าม execute failed command เก่าอัตโนมัติ
เว้นแต่ user สั่ง retry

==================================================
18. MOBILE BOTTOM NAVIGATION
==================================================

เพิ่ม navigation เช่น:

Home
Remote
Commands
Settings

Home:
connection + current state

Remote:
command input + quick commands

Commands:
command history

Settings:
Remote settings

==================================================
19. REMOTE SETTINGS
==================================================

เพิ่ม:

AutoBridge Remote Settings

- Show command confirmation
- Haptic feedback
- Auto submit text
- Remember command history
- Quick Actions
- Prefer Agent for unknown text
- Resume last feature

==================================================
20. UI
==================================================

ใช้ Dark UI ให้เข้ากับ AutoBridge

Mobile Remote:

┌──────────────────────────────┐
│ AutoBridge               ⚙  │
│ ● Android Auto Connected     │
│                              │
│ พิมพ์คำสั่งหรือข้อความ       │
│ ┌──────────────────────────┐ │
│ │ เปิด google.com         │ │
│ └──────────────────────────┘ │
│                       [ ➤ ]  │
│                              │
│ คำสั่งด่วน                   │
│                              │
│ Browser  YouTube  Maps       │
│ Spotify  Mirror   Reload     │
│ Fullscreen       Agent       │
│                              │
│ สถานะปัจจุบัน                │
│ Browser                      │
│ google.com                   │
│                              │
│ ✓ คำสั่งล่าสุดสำเร็จ         │
└──────────────────────────────┘

==================================================
21. ANDROID AUTO FEEDBACK
==================================================

หลัง execute command จากมือถือ:

สามารถแสดง small non-blocking status:

✓ เปิด google.com แล้ว
จากคำสั่งบนมือถือ

แสดงประมาณ 2-3 วินาที

ต้องไม่ block interaction

ห้ามใช้ Dialog สำหรับ command success ทั่วไป

ใช้:

Snackbar
Toast-like overlay
status banner

ตาม UI framework ที่ project ใช้อยู่

==================================================
22. DO NOT BREAK CURRENT FEATURES
==================================================

ห้ามทำให้ feature เดิมเสีย:

Home
Browser
WebView
Mirror
Media
Video
Music
Streaming
Agent
Fullscreen
Desktop Mode
Find in Page
Quick Launch
Driving-aware UI
Android Auto DHU connection

==================================================
23. TEST CASES
==================================================

ทดสอบ:

Mobile:
พิมพ์ "เปิด google.com"

Expected:
Android Auto เปิด Browser → google.com

Mobile:
กด Reload

Expected:
WebView reload

Mobile:
พิมพ์ "ภูสอยดาว"
Send Text to Screen

Expected:
ข้อความไปยัง Browser input

Mobile:
เปิด Fullscreen

Expected:
Browser fullscreen โดย state ไม่หาย

Android Auto:
กด Mirror

Expected:
Mobile Current Screen เปลี่ยนเป็น Mirror

Android Auto disconnect:

Expected:
Mobile แสดง disconnected
ส่ง command แล้วไม่ crash

Reconnect:

Expected:
state sync ใหม่ถูกต้อง

==================================================
24. BEFORE IMPLEMENTATION
==================================================

ก่อนแก้ code:

1. Inspect project
2. หา Mobile main screen
3. หา Android Auto screen
4. หา BrowserController
5. หา MirrorController
6. หา MediaController
7. หา Agent
8. หา state management
9. หา Service / Repository ที่ share state ได้
10. หา existing command/event system ถ้ามี

จากนั้นรายงาน:

- สิ่งที่มีอยู่แล้ว
- สิ่งที่ reuse ได้
- สิ่งที่ต้องเพิ่ม
- ไฟล์ที่จะต้องแก้
- architecture ที่เลือกใช้

แล้วค่อย implement

==================================================
25. FINAL OUTPUT
==================================================

หลังทำเสร็จ:

แสดง:

- ไฟล์ที่เพิ่ม
- ไฟล์ที่แก้
- Command architecture
- State sync architecture
- Supported commands
- unsupported commands
- วิธีเพิ่ม command ใหม่
- วิธีเพิ่ม Quick Action ใหม่
- วิธีทดสอบบน Mobile + Android Auto DHU

ห้ามแก้ feature อื่นนอก scope โดยไม่จำเป็น