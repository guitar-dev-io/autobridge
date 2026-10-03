# AutoCinema Installation & Setup Guide 🚗📱

This tutorial provides step-by-step instructions on how to install **AutoCinema** and enable video playback on your car's **Android Auto** head unit display using **Shizuku** (via Wireless Debugging) and **KingInstaller** (No Root Required).

---

## 📋 Table of Contents
1. [Why Shizuku & KingInstaller?](#1-why-shizuku--kinginstaller)
2. [Prerequisites](#2-prerequisites)
3. [Step 1: Enable Developer Options](#step-1-enable-developer-options)
4. [Step 2: Setup Shizuku via Wireless Debugging](#step-2-setup-shizuku-via-wireless-debugging)
5. [Step 3: Install AutoCinema using KingInstaller](#step-3-install-autocinema-using-kinginstaller)
6. [Step 4: Enable Developer Settings in Android Auto](#step-4-enable-developer-settings-in-android-auto)
7. [Step 5: Connect to Car & Enjoy Playback](#step-5-connect-to-car--enjoy-playback)
8. [Troubleshooting & FAQ](#troubleshooting--faq)

---

## 1. Why Shizuku & KingInstaller?

Google's Android Auto security policy prohibits standard third-party video players from showing up in the car launcher if installed normally (via standard APK sideloading). 
To make Android Auto recognize AutoCinema as a certified application without rooting your phone:
- **Shizuku** allows apps to execute elevated ADB commands directly on your phone using Android's native Wireless Debugging feature.
- **KingInstaller** uses Shizuku to install the AutoCinema APK with the installer package metadata spoofed as `com.android.vending` (Google Play Store), tricking Android Auto into treating it as an officially installed app.

---

## 2. Prerequisites

- An Android phone running **Android 11 or newer** (Android 11+ supports native Wireless Debugging without needing a computer).
- An active **Wi-Fi connection** (both your phone and the wireless debugging service require Wi-Fi to establish local pairing).
- Downloaded files:
  1. **AutoCinema APK** (the app package).
  2. **Shizuku** (Install from Google Play Store or download from [GitHub Shizuku Releases](https://github.com/RikkaApps/Shizuku/releases)).
  3. **KingInstaller** (Download latest APK from [KingInstaller GitHub Releases](https://github.com/fcaronte/KingInstaller/releases)).

---

## Step 1: Enable Developer Options

1. Open your phone's **Settings**.
2. Navigate to **About Phone** (or **System** > **About Phone**).
3. Find **Build Number** (on some devices like Xiaomi/Samsung, it may be under *Software information*).
4. Tap **Build Number 7 times** consecutively until you see a toast notification saying *"You are now a developer!"*.
5. Return to **Settings** > **System** (or Additional Settings) to verify **Developer Options** is now visible.

---

## Step 2: Setup Shizuku via Wireless Debugging

1. Connect your phone to any available **Wi-Fi network**.
2. Open **Developer Options** in phone settings:
   - Scroll down and turn on **Wireless Debugging**.
   - Tap *Allow* on any confirmation dialogs asking to allow wireless debugging on this network.
3. Open the **Shizuku** app on your phone:
   - Under the *Start via Wireless Debugging* section, tap **Pairing**.
   - Tap **Developer options** to jump directly to the system settings.
4. In **Wireless Debugging** settings:
   - Tap **Pair device with pairing code**.
   - A 6-digit Wi-Fi pairing code and port number will appear.
   - A notification from Shizuku will appear in your notification shade: *"Pairing service found - Enter pairing code"*.
   - Pull down your notification bar, enter the 6-digit code, and tap send/submit.
   - You will see a notification confirming *"Pairing successful"*.
5. Return to the **Shizuku** app:
   - Tap **Start** under *Start via Wireless Debugging*.
   - Within 2-3 seconds, Shizuku's status at the top will change to **"Shizuku is running"** with version information.

---

## Step 3: Install AutoCinema using KingInstaller

1. Install and open **KingInstaller**.
2. When prompted, grant KingInstaller permission to use **Shizuku**.
   - In the Shizuku authorization dialog, select **"Allow all the time"**.
3. In KingInstaller:
   - Tap **Select file** (or browse) and locate the downloaded `AutoCinema.apk`.
   - Check the option **"Enable if you have LineageOS / AOSP / Android 14+"** if applicable to your OS version.
   - Tap the **Install as king** or **Install** button.
4. Android's package installer dialog will appear showing the installation prompt. Tap **Install** or **Update**.
5. Once completed, AutoCinema is installed with the necessary Play Store installation origin flags.

---

## Step 4: Enable Developer Settings in Android Auto

Even with KingInstaller, Android Auto requires "Unknown sources" to be enabled in its developer menu:

1. On your phone, go to **Settings** > **Connected devices** > **Android Auto** (or search for *Android Auto* in Settings).
2. Scroll down to the very bottom to find the **Version** section.
3. Tap on **Version** rapidly **10 times**.
4. A popup will ask: *"Allow development settings?"* -> Tap **OK**.
5. Tap the **3 vertical dots** icon in the upper-right corner of the Android Auto screen.
6. Select **Developer settings**.
7. Scroll down and check the box for **Unknown sources**.
8. In the same menu, find **Application Mode** and ensure it is set to **Developer** or **Release**.
9. Tap the back button to save.

---

## Step 5: Connect to Car & Enjoy Playback

1. Plug your phone into your car via a high-quality USB data cable (or connect via Wireless Android Auto).
2. On your car display, open the **Android Auto app grid / launcher**.
3. You will see the **AutoCinema** icon! Tap to launch.
4. Use your phone or car screen to select videos:
   - Play offline movies from device storage or an SD card.
   - Paste online stream URLs (HLS `.m3u8`, MP4, MKV).
   - Configure dual-screen subtitle settings (Persian/English, font sizes, colors, outlines).

---

## Troubleshooting & FAQ

### Q1: The app does not appear on my car screen.
- Verify that **Unknown sources** is checked in Android Auto Developer settings.
- Make sure you installed AutoCinema using KingInstaller, not standard Android package installer.
- In Android Auto settings, check **Customize launcher** to ensure AutoCinema is checked.
- Restart your phone and reconnect to the car.

### Q2: Persian subtitles are showing as question marks (???) or distorted characters.
- Open the **Subtitles** dialog (CC icon) in AutoCinema.
- Under **Subtitle Encoding**, switch from `UTF-8` to `Windows-1256 (Persian/Arabic)`.

### Q3: Video stutters or stops when entering tunnels.
- AutoCinema includes automatic multi-level caching (90s - 150s buffer). When playing online streams, wait 5-10 seconds before driving into long tunnels so the player can pre-cache sufficient frames.

### Q4: Wireless Debugging turns off after rebooting the phone.
- Android automatically turns off Wireless Debugging after reboots or disconnecting from Wi-Fi for security reasons. Once AutoCinema is installed, you do **NOT** need to keep Shizuku running to use the app in your car! Shizuku is only needed during the initial installation or when updating the app.