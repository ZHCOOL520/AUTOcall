# AUTOcall - Automatic Phone Dialing System

> ## Legal Disclaimer
>
> - **Purpose**: This software is intended for **educational and technical research** purposes only
> - **Prohibited Uses**: **Strictly forbidden** for harassment, fraud, spam marketing, or any illegal activities
> - **Legal Responsibility**: Users bear **full legal responsibility**; the developer assumes no liability
> - **Privacy**: Please comply with local laws and **respect others' privacy**
>
> **By downloading, you acknowledge that you have read and agree to the above statements**

![Version](https://img.shields.io/badge/version-4.1.1-blue)
![Android](https://img.shields.io/badge/Android-9%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple)

A powerful Android automatic phone dialing system with support for batch calling, call audio injection, recording, and more.

**GitHub**: [ZHCOOL520/AUTOcall](https://github.com/ZHCOOL520/AUTOcall)

---

## Core Features

### Batch Dialing
- Support for Excel (.xlsx/.xls) and CSV file import
- Smart column header recognition (phone, name, account number, balance, etc.)
- **One-Click Clipboard Import**: Copy numbers and tap the button to auto-detect
- Tap a contact to initiate a call directly

### Call Audio Injection
- Inject audio directly into the call channel via `AudioTrack`
- No speaker output on your end; only the other party hears the audio
- Automatic playback stop when the call ends

### Call Recording
- Real-time recording with automatic MP3 file saving
- Works on both ROOT and non-ROOT devices (ROOT recommended for best results)
- Recording paths automatically linked to call logs

### Multi-SIM Support
- Select SIM Card 1 or SIM Card 2 for dialing
- Dual-SIM alternating mode with automatic rotation
- Default SIM mode using the system-configured SIM card

### Data Management
- **Balance & Call Count Sorting**: Toggle ascending/descending with one tap
- **Call Statistics**: Auto-track dial count, duration, and success rate
- **CSV Export**: Windows-compatible format for easy post-processing
- **Data Persistence**: Contacts and call records auto-saved, surviving app restarts

---

## Quick Start

### 1. Import Contacts
- **File Import**: Tap "Import Phone" and select an Excel/CSV file
- **Clipboard Import**: Copy numbers and tap "Import Clipboard"

### 2. Configure Settings
Tap the **Settings** icon in the top-right corner:
- **Audio Playback**: Enable and select a voice file
- **Call Recording**: Enable for automatic recording
- **SIM Card Selection**: Choose which SIM slot to use

### 3. Start Dialing
- Tap "Start Call" to begin automatic dialing
- Pause / Resume / Stop anytime
- View real-time progress and status

---

## File Structure

### Call Recordings
| Item | Description |
|------|-------------|
| **Location** | `/Android/data/com.example.autocall/files/call_records/` |
| **Format** | MP3 |
| **Naming** | `call_number_timestamp.mp3` |

### Audio Files
| Item | Description |
|------|-------------|
| **Location** | `/Android/data/com.example.autocall/files/audio/` |
| **Supported Formats** | MP3, WAV, OGG, etc. |

---

## Important Notes

### Permissions Required
| Permission | Purpose |
|------------|---------|
| `CALL_PHONE` | Making phone calls |
| `READ_PHONE_STATE` | Monitoring call state |
| `RECORD_AUDIO` | Call recording |
| `READ_MEDIA_AUDIO` | Reading audio files |

### System Limitations
- **ROOT Devices**: Recording and audio injection work best
- **Non-ROOT Devices**: Some features may be limited
- **Vendor ROMs**: Xiaomi, Huawei, etc. may require additional permissions

### Usage Tips
- Test recording in a quiet environment
- Use two phones to call each other for complete testing
- Regularly export call records to backup data

---

## Changelog

### v4.1.1 (2026-06-01) - Bug Fix & Optimization

- **Pause/Resume Fix**: Fixed the issue where resuming after pause would skip the current number; now correctly continues from the paused position
- **Clipboard Import Enhancement**: Added support for more delimiters (comma, semicolon, pipe), auto-handles spaces, dashes, parentheses, and other formats
- **Clipboard Button Improvement**: Redesigned clipboard import button with dedicated row and format hint text
- **Background Download Fix**: Added network timeout, storage check, progress throttle, and manual APK open fallback on install failure
- **Disclaimer Language Fix**: Fixed issue where disclaimer dialog showed Chinese in English mode; now synchronously initializes LanguageManager at startup

---

### v4.1.0 (2026-05-29) - Multi-language Support

- **Multi-language Support**: Added Chinese/English language switching with real-time UI text translation
- **i18n Framework**: Integrated LanguageManager for dynamic translation based on JSON configuration files
- **Language Resources**: Added zh.json/en.json covering all UI component texts
- **Component Update**: Replaced deprecated Divider with HorizontalDivider

---

### v4.0.0 (2026-05-23) - Major Version

> Due to the loss of the original release key, this update uses a new signing key!
>
> - Users cannot directly overwrite-install from v3.3.0 and earlier
> - You **must uninstall the old version** before installing the new one
> - Please export your contacts before uninstalling

- **Status Sync Fix**: Resolved call progress jumping issues during long calls
- **Dialing Logic Optimization**: Configurable call interval (1-10 seconds) and IDLE state waiting
- **Number Validation**: Strict validation for Chinese mobile phone format
- **Code Quality**: Cleaned up unused functions, eliminated compiler warnings
- **Security Enhancement**: Fixed unsafe type conversions, added null pointer checks

### v3.2.0 (2026-05-15)
- Auto-update feature with background APK download and progress display
- Network access tips and download page shortcut
- First-launch agreement optimization
- Settings page enhancements with feature introduction and privacy policy links

### v3.1.2 (2026-05-15)
- Fixed SIM card selection dialog using SubscriptionManager API
- Added READ_PHONE_STATE permission check

---

> See [CHANGELOG.md](CHANGELOG.md) for earlier version history

## FAQ

<details>
<summary><b>Q: Recording won't start?</b></summary>
<br>
A: Check if recording permission is granted. ROOT devices are recommended for best results.
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
A: Mobile numbers, landlines, international numbers (+86/+852 etc.), and short codes are all supported.
</details>

---

## License

MIT License

---

## Support & Feedback

- **Bug Reports**: [GitHub Issues](https://github.com/ZHCOOL520/AUTOcall/issues)
- **Bilibili**: [ZHCOOL520](https://space.bilibili.com/1414910921)

Feel free to submit bug reports and feature requests!

---

*This document was generated with AI assistance*
