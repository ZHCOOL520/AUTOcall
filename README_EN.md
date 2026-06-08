# Automatic Phone Dialing System (AUTOcall)

> ## ⚖️ **Legal Disclaimer**
>
> - 📚 **Purpose**: This software is intended for **educational and technical research** purposes only
> - 🚫 **Prohibited Uses**: **Strictly forbidden** for harassment, fraud, spam marketing, or any illegal activities
> - ⚖️ **Legal Responsibility**: Users bear **full legal responsibility**; the developer assumes no liability
> - 🔒 **Privacy**: Please comply with local laws and **respect others' privacy**
>
> **❗ By downloading, you acknowledge that you have read and agree to the above statements**

![Version](https://img.shields.io/badge/version-4.1.3-blue)
![Android](https://img.shields.io/badge/Android-9%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple)
![Bilibili](https://img.shields.io/badge/Bilibili-ZHCOOL520-pink)
![Email](https://img.shields.io/badge/Email-zhcool520%40foxmail.com-blue)

A powerful Android Automatic Phone Dialing System, supporting batch calling, call audio injection, recording, and more.

**Bilibili**: [ZHCOOL520](https://space.bilibili.com/1414910921) | **Email**: [zhcool520@foxmail.com](mailto:zhcool520@foxmail.com)

---

## ✨ Core Features

> **⚠️ Important Note**
>
> 🔴 **Under Development**
>
> Due to restrictions in newer Android versions, third-party apps cannot directly call the call audio interface for custom recording.
>
> This app requires **Accessibility permission** to automatically click the native recording button on the system call screen.
>
> The Accessibility service is **only used to assist recording**, does not collect any user data, and users only need to enable it once. All existing app functions remain completely unaffected.

### 📞 Batch Dialing
- ✅ Support for Excel (.xlsx/.xls) and CSV file import
- ✅ Smart column header recognition (phone, name, account number, balance, etc.)
- ✅ **One-Click Clipboard Import**: Copy numbers and tap the button to auto-detect
- ✅ Tap a contact to initiate a call directly

### 🔊 Call Audio Injection
- ✅ Inject audio directly into the call channel via `AudioTrack`
- ✅ No speaker output on your end; only the other party hears the audio
- ✅ Automatic playback stop when the call ends

### 🎙️ Call Recording
- ✅ Real-time recording with automatic MP3 file saving
- ✅ Works on both ROOT and non-ROOT devices (ROOT recommended)
- ✅ Recording paths automatically linked to call logs

### ⚙️ Multi-SIM Support
- ✅ Select SIM Card 1 or SIM Card 2 for dialing
- ✅ Dual-SIM alternating mode with automatic rotation
- ✅ Default SIM mode using the system-configured SIM card

### 📊 Data Management
- ✅ **Balance/Call Count Sorting**: One-click toggle ascending/descending
- ✅ **Call Statistics**: Automatically records call count, duration, and success rate
- ✅ **CSV Export**: Windows-compatible format for easy post-processing
- ✅ **Data Persistence**: Contacts and call records auto-saved, survives restart

---

## 🚀 Quick Start

### 1️⃣ Import Contacts
- **📁 File Import**: Tap "Import Phone" and select an Excel/CSV file
- **📋 Clipboard Import**: Copy numbers and tap "Import Clipboard"

### 2️⃣ Configure Settings
Tap the **⚙️ Settings** icon in the top-right corner:
- **🔊 Audio Playback**: Enable and select a voice file
- **🎙️ Call Recording**: Enable for automatic recording
- **💳 SIM Card Selection**: Choose which SIM slot to use

### 3️⃣ Start Dialing
- ▶️ Tap "Start Call" to begin automatic dialing
- ⏸️ Pause / Resume / Stop anytime
- 📊 View real-time progress and status

---

## 📁 File Structure

### 🎙️ Call Recordings
| Item | Description |
|------|-------------|
| **Location** | `/Android/data/com.example.autocall/files/call_records/` |
| **Format** | MP3 |
| **Naming** | `call_number_timestamp.mp3` |

### 🔊 Audio Files
| Item | Description |
|------|-------------|
| **Location** | `/Android/data/com.example.autocall/files/audio/` |
| **Supported Formats** | MP3, WAV, OGG, etc. |

---

## ⚠️ Important Notes

### 🔐 Permissions Required
| Permission | Purpose |
|------|------|
| `CALL_PHONE` | Making phone calls |
| `READ_PHONE_STATE` | Monitoring call state |
| `RECORD_AUDIO` | Call recording |
| `READ_MEDIA_AUDIO` | Reading audio files |

### 📱 System Limitations
- **ROOT Devices**: ✅ Recording and audio injection work best
- **Non-ROOT Devices**: ⚠️ Some features may be limited
- **Vendor ROMs**: ❗ Xiaomi, Huawei, etc. may require additional permissions

### 💡 Usage Tips
- 🎧 Test recording in a quiet environment
- 📞 Use two phones to call each other for complete testing
- 💾 Regularly export call records to backup data

---

## 🔄 Changelog

### v4.1.3 (2026-06-08) - **Accessibility Mode Refactor & Smart Deduplication**

- ♻️ **Feature Renaming**: Unified renaming of "Accessibility Service" to "Accessibility Mode", all user-visible text dynamically retrieved via LanguageManager
- ⚙️ **Core Logic Enhancement**: Automatically disable recording when enabling Accessibility Mode, recording state remains unchanged when disabling mode
- 🎨 **UI Layout Optimization**: Merged recording toggle and Accessibility Mode toggle into the same settings area, added description text explaining linkage
- 🌐 **i18n Improvements**: Added mode-related log strings, updated Chinese and English language files
- 🐛 **Clipboard Format Fix**: Optimized clipboard content parsing, fixed phone number recognition issues with special formats
- ⚡ **Phone List Sorting Optimization**: Added multiple sorting methods (phone number, balance, call count) with ascending/descending toggle
- ✨ **One-Click Deduplication**: Added deduplication button to automatically remove duplicate numbers and sort by phone number

---

### v4.1.2 (2026-06-03) - **Accessibility Service**

- ✨ **Accessibility Service**: Added accessibility service for dialing assistance, monitors window state to optimize dialing flow
- ✨ **Accessibility Toggle**: Added accessibility service toggle in settings with quick access to system settings
- 📝 **Agreement Update**: Updated user agreement with accessibility permission description, clarified no user data collection
- 🔧 **Privacy Protection**: Accessibility service only used for dialing assistance, no personal data collected or uploaded

> **⚠️ Recording Feature Note**
>
> 🔴 **Under Development**
>
> Due to restrictions in newer Android versions, third-party apps cannot directly call the call audio interface for custom recording.
>
> This app requires the Accessibility permission to automatically click the native recording button on the system call screen.
>
> **This update (v4.1.2) only adds the Accessibility service configuration; the recording feature itself is not yet implemented**.
>
> Users only need to enable the Accessibility service once, and all existing app functions remain completely unaffected.

---

### v4.1.1 (2026-06-01) - **Bug Fix & Optimization**

- 🐛 **Pause/Resume Fix**: Fixed the issue where resuming after pause would skip the current number (pending verification)
- ✅ **Clipboard Import Enhancement**: Added support for more delimiters (comma, semicolon, pipe), auto-handling spaces, hyphens, brackets
- 🎨 **Clipboard Button Improvement**: Redesigned clipboard import button with format hint text
- 🔧 **Background Download Fix**: Added network timeout, storage check, progress throttle; guides user to manually open APK on install failure
- 🌐 **Disclaimer Language Fix**: Fixed issue where disclaimer dialog showed Chinese in English mode; sync LanguageManager initialization on startup

---

### v4.1.0 (2026-05-29) - **Multi-language Support**

- 🌐 **Multi-language Support**: Added Chinese/English language switching with real-time UI text translation
- 🌐 **i18n Framework**: Integrated LanguageManager for dynamic translation based on JSON configuration files
- 📝 **Language Resources**: Added zh.json/en.json covering all UI component texts
- 🔧 **Component Update**: Replaced deprecated Divider with HorizontalDivider

---

# ⚠️⚠️⚠️ v4.0.0 (2026-05-23) - **Major Version** ⚠️⚠️⚠️

> # 🔴🔴🔴 **Important Warning: Signing Key Change** 🔴🔴🔴
>
> ### 💥 **Due to the loss of the original release key, this update uses a new signing key!**
>
> ---
>
> #### 🚨 **Key Impact (Must Read):**
>
> | Status | Description |
> |------|------|
> | ❌ **Prohibited** | **Cannot overwrite install**: Users cannot upgrade from v3.3.0 or earlier via in-app update or direct install |
> | ❌ **Error** | **Signature mismatch**: Attempting direct install will prompt "signature mismatch" causing install failure |
> | ✅ **Correct Way** | **Must uninstall old version** (v3.3.0 and earlier), then **reinstall new version** |
>
> ---
>
> #### 💾 **Data Backup Recommendation (Strongly Recommended):**
>
> 1. 📤 Before uninstalling, use the **"Export Contact List"** feature to backup your contacts
> 2. 📁 Call records are also exported with contacts, save the CSV file securely
> 3. ⚙️ Settings (audio selection, SIM card mode, etc.) need to be reconfigured in the new version
>
> ---
>
> **⚠️ Please read the above carefully to avoid data loss and install failure!**

------

#### 📋 Technical Updates

- 🐛 **Status Sync Fix**: Resolved call progress jumping issues during long calls, added atomic index protection
- ⚙️ **Dialing Logic Optimization**: Configurable call interval (1-10s) and IDLE state waiting to prevent misdialing
- 🔒 **Number Validation**: Strict validation for Chinese mobile phone format (11-digit starting with 1), preventing non-number fields from being dialed
- 🧹 **Code Quality**: Cleaned up unused functions, eliminated compiler warnings, optimized GlobalScope usage
- 🛡️ **Security Enhancement**: Fixed unsafe type conversions, added AudioManager and ClipboardManager null pointer checks

---

> 📋 See [CHANGELOG.md](CHANGELOG.md) for earlier version history

## 🐛 FAQ

<details>
<summary><b>Q: Recording won't start?</b></summary>
<br>
A: Check if recording permission is granted. ROOT devices are recommended.
</details>

<details>
<summary><b>Q: The other party can't hear the audio?</b></summary>
<br>
A: Confirm the audio file exists and check if the audio playback switch is enabled.
</details>

<details>
<summary><b>Q: How to export contacts?</b></summary>
<br>
A: Tap the "Export Contact List" button at the bottom and choose a save location.
</details>

<details>
<summary><b>Q: What phone number formats are supported?</b></summary>
<br>
A: Mobile numbers, landlines, international numbers (+86/+852 etc.), short codes.
</details>

---

## 📄 License

MIT License

---

## 🤝 Support & Feedback

- 🐛 **Bug Reports**: [GitHub Issues](https://github.com/ZHCOOL520/AUTOcall/issues)
- 📧 **Email**: [zhcool520@foxmail.com](mailto:zhcool520@foxmail.com)

Feel free to submit bug reports and feature requests!

---

*This document was generated with AI assistance*
