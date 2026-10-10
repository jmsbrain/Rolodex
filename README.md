# Rolodex

A small Android app for the Light Phone III that shows **everything** saved on a contact. The built-in LightOS contacts tool shows only one number per contact.

For each contact it shows:

- every phone number with its label (mobile, work, home, custom…), each with a **PING** button
- every email address. Tap one to copy it.
- every postal address. Tap one to open the phone's maps app.
- company, title and department
- nicknames, birthdays, anniversaries and other dates
- websites, messaging handles, related people and notes
- the full name breakdown (prefix, middle name, suffix, phonetic), when there is one

**Long-press any item to copy it.** If an action has no app to handle it on the phone, the value is copied instead. Tap the **X** in the search box to clear it.

## PING

PING, or tapping the number itself, asks LightOS to open its own phone screen with the number filled in. It asks the same way Light SDK tools do. From there you can call, or start a text with LightOS's usual steps. If LightOS doesn't answer, the app sends a normal dial request instead.

LightOS only answers sideloaded apps when the phone is set to allow **"Any tools"**.

## As the phone's contacts app

Rolodex tells Android it can act as a contacts app. If anything on the phone asks Android to "show contacts" or "show this contact", Android can offer Rolodex. This includes Android's own Settings → Apps → Default apps, where available. Whether the contacts button inside the LightOS dialer uses this depends on LightOS. If LightOS opens its own screen directly, Rolodex can't take over that button.

All text is 20% larger than LightOS's standard sizes. To change it, edit `TEXT_SCALE` in `LightStyle.kt`.

It reads the phone's contacts directly, so edits made elsewhere show up the next time you open it. Nothing leaves the phone, and the app has no internet permission.

## Why this isn't a Light SDK tool

The Light SDK doesn't allow tools to read the phone's contacts: `READ_CONTACTS` isn't on its permission allow-list, and `contentResolver` is blocked. This app is a plain Android app instead. It's styled to match LightOS: black background, Akkurat font when the phone has it, and LightOS text sizes. Because Light won't sign it, it has to be **sideloaded**.

## Build it

You need **Android Studio** (any recent version).

1. In Android Studio, choose **File → Open** and select this folder (`Light Tool`).
2. Wait for the Gradle sync to finish. The first sync downloads Gradle 9.0 and the Android libraries.
3. Choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

You can also build from a terminal in this folder with `gradlew.bat assembleDebug`. This needs a JDK 17+ and the Android SDK; Android Studio sets both up.

## Install it on the Light Phone III

1. On the phone, enable Developer options and USB debugging.
2. Connect the phone to the computer by USB, then run:

   ```
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

3. Open the app. It asks for contacts permission on first launch. If no permission prompt appears, which can happen on LightOS, grant it from the computer:

   ```
   adb shell pm grant com.jms.fullcontacts android.permission.READ_CONTACTS
   ```

4. If LightOS doesn't list the app yet, you can launch it with:

   ```
   adb shell am start -n com.jms.fullcontacts/.MainActivity
   ```

   Once LightOS ships support for community tools, choose the **"Any tools"** option to show sideloaded apps.

## Test without the phone

You can run the app in an Android emulator: API 34, 1080×1240, no Google Play. Add a few contacts with several numbers and emails in the emulator's Contacts app, then click **Run** in Android Studio.

## Project layout

```
app/src/main/kotlin/com/jms/fullcontacts/
  MainActivity.kt        permission, ping / map / copy actions, opening a contact from other apps
  App.kt                 screens: permission, contact list with search, contact detail
  ContactsRepository.kt  reads every field from Android's contacts database
  LightStyle.kt          LightOS-like font, colors and text sizes (TEXT_SCALE)
  LightOsBridge.kt       asks LightOS to open its own dialer
```
