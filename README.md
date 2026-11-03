# Zorken (LSPosed module + app) - v1.0.0

Hides selected apps and files after N wrong phone-unlock attempts. Restore only from this app with your secret PIN.
A correct phone unlock restores nothing.

## Setup
1. Build the APK (Android Studio, or push to GitHub: Actions -> latest run -> Artifacts, or Releases -> "Latest build").
2. Install it, enable the module in LSPosed (scope: **System Framework**), reboot.
3. Open the app, set a secret PIN (not your phone lock PIN), choose apps, add folders/files, set the attempt limit.
4. Grant **All files access** (needed to hide files). Allow notifications (progress). No root needed.
5. Recommended: disable battery optimization for Zorken. Unlock the app once after installing so the lock key is created.

## How it works
- **Wrong attempts** are counted by the module inside system_server. At the limit it hides the apps and asks the app to hide the files.
- **Hide files**: each item is moved instantly into the hidden `.zorken` folder at the root of its storage volume.
  The module delivers this request with a temporary Android allowlist and puts the app on the battery-optimization allowlist,
  so the locking below can start in the background even though nobody opened the app.
- **Lock**: a foreground service (`LockService`, notification with progress) encrypts the item into `.zorken/<id>.<n>.zk`
  (AES-256-GCM, fresh key per container). It works in small steps (up to ~256 MB each, a big file gets its own container):
  each container is verified before the plain files it holds are deleted, so an interruption never loses progress and
  the next run continues with what is left. Free space needed is about one file, not the whole item.
  If Android refuses to start the service, a JobScheduler job (`EncryptJob`) does the same work.
- **Restore**: also runs in the foreground service (notification + progress). The app stays on "Hidden" until it is done;
  if interrupted, swipe to restore again. A container is deleted only after everything in it is back; existing files are never overwritten.
- **Progress bars** show hiding (items), locking (bytes written + verified) and restoring (bytes) on the main screen and in the notification.
- Locking needs no PIN. Unlocking needs the PIN **and** a device secret kept by the module (`/data/system/zk_config`),
  so copying `.zorken` off the phone is not enough to brute-force a short PIN. A copy of the key bundle (`.zorken/.keys`) stays next to the files so a reinstall keeps working.

## Settings survive reinstall / update
The module keeps PIN hash, apps, folders, attempt limit and device secret in `/data/system/zk_config`, which uninstalling the app does not touch.
After a reinstall the app shows "Saved settings found - enter your PIN to restore them".
After updating the APK, **reboot once** when the status card says "older version" so the new module code loads
(module protocol version 4; changes that only touch the app need no reboot).

## Recovery
- Something breaks: disable the module in LSPosed and reboot.
- Forgot PIN: with root delete `/data/system/zk_config`, reboot, reinstall the app. Files that were already locked cannot be opened without that secret.
- Files stuck as plain files in `.zorken` (interrupted locking): open the app (it resumes) or swipe to restore.

## UI
Flat list layout with a lime accent and a corner glow. Icons are vector drawables in `res/drawable` (`ic_*.xml`).
Optional: put a font at `app/src/main/assets/fonts/ui.ttf` (e.g. Outfit or Sora) and the whole UI uses it; without it the system font is used.

## Source layout (`app/src/main/java/com/zorken`)
| File | Role |
|---|---|
| `Hook.java` | Module, runs in system_server: counts wrong attempts, hides apps, stores config, answers the app |
| `MainActivity.java` | UI: PIN screens, status, cards, progress bar |
| `Shell.java` | Hide/restore/lock logic, path rules, progress tracking |
| `Vault.java` | Pure-Java encryption (key bundle, containers, chunked AES-GCM) |
| `LockService.java` | Foreground service: locking and restoring |
| `AppLog.java` | One persistent log (`files/zorken.log`, ~256 KB cap) shown on the **Logs** card: hide/lock/restore, module events, settings, crashes |
| `ZorkenApp.java` | Writes app crashes to the log |
| `EncryptJob.java` | Fallback job for locking; `schedule()` starts the service first |
| `FileReceiver.java` | Receives the module's answers and the "hide files" request |
| `Common.java` | Shared helpers and the module protocol version |

## Automatic builds
Every push builds the APK with GitHub Actions and signs it with a key kept in the Actions cache.
If that cache is ever evicted you must uninstall the old app before installing a new one.

## Hide app icon
Settings > **Hide app icon** (Off / On pill) disables the launcher alias (`.LauncherAlias`). Open Zorken afterwards from LSPosed > Modules > Zorken (module settings),
then switch it off to bring the icon back. Reinstalling the app also brings the icon back.

## Logs
The **Logs** card (newest first, Refresh / Copy / Clear) lists everything the app did. Module events (wrong attempts, apps hidden/shown) are sent
by the hook, so they appear after one reboot following the update.

## Display size
The **Display size** card scales the app's own interface (70%-140%, 10% steps) with a -/+ pill. It does not change the phone's DPI.
