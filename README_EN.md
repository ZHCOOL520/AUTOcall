# AUTOcall - Automatic Phone Dialing System

> ## Legal Disclaimer
>
> - **Purpose**: This software is intended for **educational and technical research** purposes only
> - **Prohibited Uses**: **Strictly forbidden** for harassment, fraud, spam marketing, or any illegal activities
> - **Legal Responsibility**: Users bear **full legal responsibility**; the developer assumes no liability
> - **Privacy**: Please comply with local laws and **respect others' privacy**
>
> **By downloading, you acknowledge that you have read and agree to the above statements**

![Version](https://img.shields.io/badge/version-4.1.0-blue)
![Android](https://img.shields.io/badge/Android-9%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple)

A powerful Android automatic phone dialing system with support for batch calling, call audio injection, recording, and more.

**GitHub**: [ZHCOOL520/AUTOcall](https://github.com/ZHCOOL520/AUTOcall)

---

## Core Features

### Batch Dialing
- Support for Excel (.xlsx/.xls) and CSV file import
- Smart column header recognition (phone, name, account number, balance, etc.)
- **Clipboard Import**: One-click phone number extraction
- Tap a contact to call directly

### Audio Injection
- Direct audio injection into the call channel via `AudioTrack`
- No speaker output on your end; only the other party hears the audio
- Automatic playback stop when the call ends

### Call Recording
- Real-time recording functionality (ROOT permission recommended for best results)
- MP3 format with automatic file saving
- Recording path written to call logs

### Multi-SIM Support
- Select SIM Card 1 / SIM Card 2
- Dual-SIM alternating dialing mode
- Default SIM card mode

### Data Management
- **Balance Sorting**: Ascending/descending toggle
- **Call Statistics**: Track dialing counts
- **Export Function**: CSV format, Windows-compatible
- **Data Persistence**: Auto-save contacts and call records

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

### v4.1.0 (2026-05-29) - New Feature

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

### v3.1.1 (2026-05-15)
- Fixed version comparison logic using semantic versioning

### v3.0.0 (2026-05-15)
- UI restructure with independent settings page
- SIM card selection optimization with dialog interface
- Audio management optimization

### v2.1.0 (2026-05-06)
- Clipboard import feature
- Enhanced phone number recognition algorithm
- Smart deduplication and append mode

### v2.0.0 (2026-05-03)
- Data persistence feature
- Call count statistics
- Windows CSV compatibility
- MP3 recording format

---

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
