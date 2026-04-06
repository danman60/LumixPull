# LumixPull

Android app that transfers JPEG photos from a **Panasonic Lumix S5II** (DC-S5M2X) to your phone via USB-C.

Built because the official LUMIX Sync app is unreliable for photo transfer.

## How It Works

Uses MTP (Media Transfer Protocol) over USB to directly read photos from the camera and save them to your phone's gallery via MediaStore. Photos appear in Google Photos automatically.

## Setup Instructions

### 1. Camera Settings

On your Lumix S5II:

1. Press **MENU**
2. Go to **Setup** (wrench icon)
3. Select **USB**
4. Set USB Mode to **PC(Tether)**

> **Important:** The camera MUST be in **Tether** mode. Storage mode and LUMIX Flow mode will not work with this app.

### 2. Install the App

1. Download `LumixPull-v1.0.0-release.apk` from the Releases page
2. Open the APK on your phone
3. Allow installation from unknown sources if prompted
4. Install and open

### 3. Connect & Transfer

1. Connect your camera to your phone with a **USB-C to USB-C cable**
2. Turn on the camera
3. The app will auto-detect the camera and request USB permission — tap **Allow**
4. The app scans for photos (takes a few seconds)
5. Tap **Test (3 photos)** first to verify everything works
6. Check **Pictures/Lumix/** on your phone — you should see the photos
7. Tap **Transfer Photos** to pull all JPEGs from the card

### 4. Google Photos

After the first transfer, open Google Photos:
- Go to **Library** → **Photos on device** → **Lumix**
- Photos will appear in your main timeline and back up automatically

## Features

- **Fast scan** — no slow pre-scan, starts transferring immediately
- **Duplicate detection** — skips photos already in your gallery
- **Auto-connect** — detects camera automatically when plugged in
- **Disconnect handling** — graceful recovery if cable is unplugged
- **Debug log** — copyable diagnostic info for troubleshooting
- **Crash recovery** — shows crash details on next launch if the app crashes

## Compatibility

- **Camera:** Panasonic Lumix S5II (DC-S5M2X). May work with other Panasonic cameras using MTP.
- **Phone:** Android 8.0+ (API 26+) with USB-C OTG support
- **Files:** Transfers JPEG files only (.jpg, .jpeg)

## Transfer Speed

MTP protocol limits throughput to roughly **2-3 MB/s** (~10 seconds per 25MB photo). This is a protocol limitation, not an app limitation. For bulk transfers of hundreds of photos, consider transferring via a computer instead.

## Building from Source

Requires Android SDK with build tools 35.0.0+ and JDK 17.

```bash
./gradlew assembleRelease
```

APK will be at `app/build/outputs/apk/release/app-release.apk`.

## License

MIT
