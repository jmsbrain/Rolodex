# Rolodex

A full contacts app for the **Light Phone III**, styled to match LightOS.

The contacts screen built into LightOS shows one number per contact. Rolodex shows and edits everything saved on a contact, keeps it in sync with your contacts server, and handles the contact requests other apps send to Android's contacts app.

<p>
  <img src="docs/screenshots/list.png" width="200" alt="Contact list, searching for Lumen">
  <img src="docs/screenshots/contact.png" width="200" alt="Contact card with phone numbers and emails">
  <img src="docs/screenshots/contact-more.png" width="200" alt="Contact card, scrolled to address and work">
  <img src="docs/screenshots/share.png" width="200" alt="Share menu: as text or as a .vcf file">
  <img src="docs/screenshots/editor.png" width="200" alt="Editor with the phone type dropdown open">
</p>

*Screenshots from a Light Phone III, using made-up demo contacts.*

**[Download the latest APK](https://github.com/jmsbrain/Rolodex/releases/latest)**, then follow [Install on the Light Phone III](#install-on-the-light-phone-iii).

## Features

### Browse
- Every contact in one searchable list. Tap the **X** to clear the search.
- Each contact card shows, in this order: full name breakdown (when there's a prefix, suffix or phonetic name), phone numbers, emails, addresses, work (company, title, department), nicknames, related people, messaging handles, SIP addresses, websites, dates (birthday, anniversary…), and notes.
- **Tap a phone number** to open the LightOS phone screen with it filled in. From there, call or text as usual.
- **Tap an email** to copy it. **Tap an address** to open maps. **Tap a website** to open it.
- **Long-press anything** to copy it. If no app on the phone can handle an action, the value is copied instead.

### Edit
- **EDIT** on a contact card opens the editor. **ADD** next to the search box creates a new contact.
- Every field above can be edited, except phonetic names. Each type label (MOBILE, HOME, SIGNAL…) is a dropdown. **X** removes a line, and **ADD PHONE**, **ADD EMAIL** and so on add one.
- Dates are typed as `MM/DD/YYYY`, or `MM/DD` when there's no year.
- Nothing is written until **SAVE**. CANCEL, or the back button, asks before throwing away changes.
- **DELETE CONTACT**, at the bottom of the editor, asks you to confirm. Deleting can't be undone.

### Group text
- **TEXT** next to the search box lists everyone with a phone number. Tap people to pick them. A filled circle marks who's in.
- For someone with more than one number, tapping opens a menu of their numbers (MOBILE, WORK…) to choose from, plus **REMOVE** once they're picked. The chosen number shows under their name.
- **TEXT (3)** sets up a group conversation with everyone picked and switches to LightOS's messages, where it's at the top of the list. Tap it and type.
- LightOS ignores recipients sent from other apps, so Rolodex briefly opens Android's built-in messaging app (`com.android.mms`). That app creates the conversation, and closes itself as Rolodex switches to LightOS. LightOS only reloads its list when it changes screens, so Rolodex opens LightOS's settings for a moment, then its messages. You'll see both flash by. The switch uses `lightos://settingsindex` and `lightos://recentsindex`, the links the Luma launcher uses. Light doesn't publish them, so a LightOS update could break this. Without the built-in app, Rolodex just opens the phone's messaging app.

### Share
- **SHARE** on a contact card sends the contact through SMS, Signal, WhatsApp or any other app on the share menu, in one of two forms:
  - **As text:** the name followed by "Mobile: …", "Email: …" and so on. Notes are left out.
  - **As contact file (.vcf):** Android's own vCard export, with every field, notes included.

### Works with other apps
Rolodex answers the same requests as Android's stock Contacts app:

| When another app wants to… | Rolodex shows… |
|---|---|
| add a new contact ("Add to contacts") | the editor, filled in with what the app sent |
| add details to an existing or new contact | the contact list with a **NEW** button, then the editor |
| edit a contact | the editor for that contact |
| pick a contact, phone number, email or address | a searchable list. Your choice goes back to the app |
| show a contact (including quick contact cards) | that contact's card |
| open a `.vcf` file | the editor for one contact, or a list with **IMPORT ALL** for several |

The editor and picker return you to the app that asked.

## Syncing

Rolodex can sync with a **CardDAV** contacts server (mailbox.org, Fastmail, Nextcloud, iCloud…) by itself, with no DAVx5 needed. The contacts still live in the phone's own contacts store, so LightOS's phone and messages show their names as before.

### Set it up
1. Tap the sync line under the search box (it says **SYNC: OFF** until you sign in).
2. Enter your email, password and server (`https://dav.mailbox.org/` is filled in). If your account uses two-factor sign-in, make an app password and use that.
3. Rolodex finds your address books and syncs the main one. Tap others to sync them too. Turning a book off removes its contacts from the phone, not from the server.
4. With more than one book, **NEW CONTACTS GO TO** picks where contacts made on the phone are saved.

### How it syncs
- **When:** right after you save, delete or import in Rolodex. Also when you open Rolodex (if the last sync was over 10 minutes ago), every hour in the background if Android allows it, and when you tap **SYNC NOW**.
- **Both ways:** changes on the phone go up, and changes made elsewhere come down. If the same contact changed in both places, the merge goes kind by kind (phones, emails, addresses…). For each kind, the phone's version wins if you changed it there, and the server's is kept otherwise.
- **Nothing extra is lost:** Rolodex only rewrites the parts of a contact it shows. Photos, groups/categories and fields it doesn't know stay on the server untouched. Photos aren't copied to the phone.
- **Numbers the server can't hold stay on the phone.** mailbox.org runs Open-Xchange, which has fixed places for numbers: two mobile, two work, two home, one other, plus fax, pager, car and a few more. A third mobile number, or one with only a custom label, may be dropped. After every upload Rolodex reads the contact back. Anything the server didn't keep stays on the phone and is listed under **ONLY ON THIS PHONE** on the sync screen, and the line under the search box says **SOME DETAILS ONLY ON THIS PHONE**. To move one onto the server, change its type in the editor (say, a third mobile to OTHER). The note clears once the server keeps it.
- **If the account disappears** (something removes it, or Android drops it), Rolodex adds it back the next time it opens or syncs, and downloads your contacts again. Edits that hadn't synced yet are lost, so sync happens right after each save.
- The line under the search box shows how sync is going (e.g. **SYNCED 5 MINUTES AGO**, or **SYNC PROBLEM: TAP FOR DETAILS**). Tap it for the sync screen.
- **SIGN OUT** removes the account and its contacts from the phone. They stay on the server.

### Moving from DAVx5
Sign in to Rolodex and let it sync. Until you remove DAVx5, each contact is stored twice on the phone, but Rolodex shows it once. Then remove the address book in DAVx5, or uninstall DAVx5. The sync screen lists any other app still syncing contacts, with how many it holds.

### Without Rolodex's sync
If you're not signed in, Rolodex edits whatever already syncs your contacts:
- **New contacts** are saved to the account that holds most of your contacts among those that sync back to a server. This is your main address book, not a phone-only contact.
- **Merged contacts** often have copies from other apps such as WhatsApp and Signal/Molly. Rolodex writes new details to the main address book's copy, so the changes sync.
- Duplicate copies of the same number or email are edited and removed together.

## Making it the default contacts app

Android has no "default contacts app" setting. When an app asks to show, add or pick a contact, Android either asks which app to use, or uses the only one installed.

- **Simple:** when Android asks, choose **Rolodex → Always**. Android remembers this separately for each kind of request.
- **Thorough:** disable the stock Contacts app so Rolodex is the only choice. Your contacts aren't affected:
  ```bash
  adb shell pm disable-user --user 0 com.android.contacts
  ```
  To undo it:
  ```bash
  adb shell pm enable com.android.contacts
  ```
  While the stock app is disabled, its screens for SIM contact import, merging duplicates and account settings aren't available.

**The contacts button in the LightOS dialer** opens LightOS's built-in contacts screen and doesn't ask Android. No app can take over that button. Open Rolodex from the home screen instead.

## LightOS notes

- Rolodex asks LightOS to open its phone screen the same way Light SDK tools do. LightOS only answers sideloaded apps when the phone is set to allow **"Any tools"**. Otherwise Rolodex sends a normal dial request.
- Text sizes follow LightOS's design scale. The contact list is 30% larger than standard (`LIST_TEXT_SCALE`), and everything else is 20% larger (`TEXT_SCALE`). Both are set in `LightStyle.kt`.
- It uses the phone's Akkurat font when the font is present.

## Why it isn't a Light SDK tool

The Light SDK doesn't let tools read or write contacts: `READ_CONTACTS` isn't on its permission allow-list, and `contentResolver` is blocked. Rolodex is a plain Android app instead. Light doesn't sign it, so it has to be **sideloaded**.

## Privacy

Rolodex needs **Contacts** permission to read and edit contacts, and **internet** permission for sync. It only connects to the server you sign in to, and only once you've signed in. Your password is stored on the phone, encrypted with a key kept in Android's Keystore. Otherwise, contact details only leave the phone when you share a contact.

## Build

You need **Android Studio**, or JDK 17+ and the Android SDK.

- **Android Studio:** **File → Open** this folder, wait for the Gradle sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
- **Terminal:** run `gradlew.bat assembleDebug`. If `JAVA_HOME` isn't set, point it at Android Studio's bundled JDK first:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

### Testing sync

- `gradlew.bat :app:testDebugUnitTest` checks how contacts turn into vCards and back, and how changes merge (`SyncCardTest.kt`).
- `tools/MockCardDav.java` is a fake CardDAV server with made-up contacts. Run it with `java tools/MockCardDav.java`. Then, on the emulator, sign in with any email and password, and the server `http://10.0.2.2:5233/`. Debug builds allow plain http to that address only. Run it as `java tools/MockCardDav.java ox` to have it drop numbers the way Open-Xchange might.

## Install on the Light Phone III

1. Download `Rolodex-<version>.apk` from the [latest release](https://github.com/jmsbrain/Rolodex/releases/latest), or [build it yourself](#build).
2. On the phone, turn on Developer options and USB debugging, then connect it by USB.
3. Install, or update over an existing install:
   ```bash
   adb install -r Rolodex-2.0.0.apk
   ```
   Use the file name you downloaded. For your own build, it's `app\build\outputs\apk\debug\app-debug.apk`.
3. Open Rolodex and allow Contacts access. If no prompt appears, grant it from the computer:
   ```bash
   adb shell pm grant com.jms.fullcontacts android.permission.READ_CONTACTS
   ```
   ```bash
   adb shell pm grant com.jms.fullcontacts android.permission.WRITE_CONTACTS
   ```
4. If it doesn't show up in LightOS, launch it with:
   ```bash
   adb shell am start -n com.jms.fullcontacts/.MainActivity
   ```

## License

Rolodex is open source under the [MIT License](LICENSE). You're free to use, change and share it, as long as you keep the copyright notice.

<!--
Version history: kept in this file but hidden on the GitHub page.

## Versions

Versions follow **MAJOR.MINOR.PATCH**: MINOR goes up for new features, and PATCH for fixes and tweaks. Each release is tagged `vX.Y.Z`. Downloadable APKs are on the [Releases page](https://github.com/jmsbrain/Rolodex/releases), from 1.5.1 on.

| Version | Changes |
|---|---|
| 2.0.0 | Syncs with a CardDAV server by itself (no DAVx5): sign in, pick address books, two-way sync with merging |
| 1.6.0 | Group text: TEXT picks several people, choosing which number for anyone with more than one |
| 1.5.1 | Dropdowns open above their label when there's no room below; tidier name hints |
| 1.5.0 | Answers other apps (add, edit, pick, quick contact, .vcf import); SHARE as text or .vcf |
| 1.4.3 | Tighter gap under search; email above address |
| 1.4.2 | Larger list text; dropdowns for field types |
| 1.4.1 | Contact card uses the editor's section order |
| 1.4.0 | Every field editable; new contacts save to the synced account |
| 1.3.0 | Edit, add and delete contacts |
| 1.2.0 | Contacts viewer |
-->

## Project layout

```
app/src/main/kotlin/com/jms/fullcontacts/
  RolodexActivity.kt     shared base: permissions, dial / map / copy / share actions
  MainActivity.kt        the app: list, contact cards, editor; opens contacts other apps link to
  RequestActivity.kt     answers other apps: add, edit, pick, .vcf import
  App.kt                 screens: permission, contact list, contact card, menus
  ContactEditor.kt       the editor screen and its dropdowns
  ContactsRepository.kt  reads contacts for the list, cards, picker and sharing
  ContactWriter.kt       loads contacts for editing and writes changes back
  Prefill.kt             details sent by other apps, applied to the editor
  VCard.kt               reads .vcf files (vCard 2.1, 3.0, 4.0)
  LightStyle.kt          LightOS-like font, colors and text sizes
  LightOsBridge.kt       asks LightOS to open its own phone screen
  SyncScreen.kt          sign-in, address books and sync status
  SyncSettings.kt        the sign-in (password encrypted) and the Android account
  CardSync.kt            two-way sync between the server and the phone
  CardDav.kt             talks to the CardDAV server
  SyncCard.kt            vCards to contact rows and back, and merging
  SyncServices.kt        lets Android run syncs and list the account
tools/MockCardDav.java   fake server for testing sync
```
