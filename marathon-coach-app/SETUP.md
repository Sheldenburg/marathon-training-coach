# Marathon Coach App — Setup Guide

One-time setup, ~20 minutes. After this, runs sync automatically forever.

---

## Overview

```
Galaxy Watch 6 → Samsung Health → Health Connect → [this app] → Google Drive → Claude (coach)
```

The app lives on your phone. GitHub builds the installer (APK) for you — no dev tools needed on your machine.

---

## Step 1 — Create a Google Cloud project & OAuth credentials

This lets the app upload to *your* Google Drive.

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and sign in with the Google account whose Drive you want to use.
2. Click the project dropdown (top left) → **New Project** → name it `marathon-coach` → Create.
3. In the left sidebar: **APIs & Services → Library**.
4. Search for **Google Drive API** → click it → **Enable**.
5. Go to **APIs & Services → OAuth consent screen**:
   - Choose **External** → Create.
   - App name: `Marathon Coach` | Support email: your email.
   - Skip Scopes for now → Save and Continue through all steps → **Back to Dashboard**.
   - Click **Publish App** (otherwise it stays in test mode and tokens expire after 7 days).
   - You'll see a warning about verification — for a personal app you can ignore it and click "Confirm".
6. Go to **APIs & Services → Credentials** → **+ Create Credentials → OAuth client ID**:
   - Application type: **Web application** (we use this type with a custom redirect URI).
   - Name: `Marathon Coach Android`.
   - Under **Authorised redirect URIs**, add: `com.marathoncoach://oauth2callback`
   - Click **Create**.
7. You'll see a popup with **Client ID** and **Client Secret**. Copy both — you need them in Step 3.

---

## Step 2 — Fork the repo & generate a signing keystore

The keystore is what makes the app uniquely yours. Generate it once on your computer (any OS, just needs Java installed — which most Macs/PCs have):

```bash
keytool -genkey -v \
  -keystore coach.keystore \
  -alias marathoncoach \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Marathon Coach, OU=Personal, O=Personal, L=Auckland, S=Auckland, C=NZ"
```

Replace `YOUR_STORE_PASSWORD` and `YOUR_KEY_PASSWORD` with passwords you choose (write them down).

Then convert it to base64 (needed for GitHub):
```bash
# macOS / Linux
base64 -i coach.keystore | pbcopy   # copies to clipboard

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("coach.keystore")) | Set-Clipboard
```

**Do not commit `coach.keystore` to GitHub.** Keep the file safe — if you lose it you'll need to reinstall the app.

Now fork this repo on GitHub (your account → Fork button), then go to:

**Your fork → Settings → Secrets and variables → Actions → New repository secret**

Add these five secrets:

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 string you copied above |
| `KEYSTORE_PASSWORD` | The store password you chose |
| `KEY_ALIAS` | `marathoncoach` |
| `KEY_PASSWORD` | The key password you chose |
| `GOOGLE_CLIENT_ID` | From Step 1 (ends in `.apps.googleusercontent.com`) |
| `GOOGLE_CLIENT_SECRET` | From Step 1 |

---

## Step 3 — Trigger the build & get the APK

1. In your fork, go to **Actions → Build APK → Run workflow** (or just push any change).
2. Wait ~3 minutes for the build to finish.
3. Click the completed run → scroll down to **Artifacts** → download `marathon-coach-app.zip`.
4. Unzip it — you'll have `app-release.apk`.
5. Transfer the APK to your Galaxy phone (AirDrop, email yourself, Google Drive, USB — any way).

---

## Step 4 — Install the app on your phone

1. On your Galaxy phone, open **Settings → Apps → Special app access → Install unknown apps**.
2. Find your file manager (or Files app) → enable "Allow from this source".
3. Open the APK file → tap Install.
4. The app "Marathon Coach" now appears on your phone.

---

## Step 5 — Grant permissions (in the app)

Open the Marathon Coach app:

**Button 1: Connect Google Drive**
- Taps opens a browser → you sign into Google → tap "Allow".
- Browser redirects back to the app automatically.
- You'll see "✅ Google Drive Connected".

**Button 2: Grant Health Connect Access**
- Opens Health Connect permission screen.
- Grant access to: Exercise, Distance, Heart Rate, Speed.
- (Samsung Health should already be sharing these with Health Connect by default.)

That's it. The app now syncs automatically every 6 hours in the background.

---

## Step 6 — Check Samsung Health is sharing data

On your Galaxy phone:
1. Open **Health Connect** (search for it or find it in Settings → Health Connect).
2. Tap **App permissions**.
3. Make sure **Samsung Health** has read access to: Exercise, Distance, Heart Rate, Speed/Pace.

If Samsung Health isn't listed, open Samsung Health → tap your profile → Settings → scroll to **Connected services** → Health Connect → enable it.

---

## Step 7 — Fix battery optimisation (important on Samsung!)

Samsung's battery optimiser will kill background apps. To stop it killing the sync:

1. **Settings → Apps** → find **Marathon Coach**.
2. Tap **Battery** → set to **Unrestricted** (not "Optimised").

Without this, the 6-hour auto-sync may not fire.

---

## Using the coach

Once a run appears in your Google Drive `RunningCoach/` folder, open Claude Code and say something like:

> "Check my training — what should I focus on this week?"

Claude reads your run files and coaches you. To give Claude richer context about your goals, see `coach.md` in this repo.

---

## Troubleshooting

**"No runs showing up in Drive"**
- Check battery optimisation (Step 7).
- Open the app and tap "Sync Now" to force an immediate sync.
- Make sure the run was recorded as a "Running" type in Samsung Health (not just Steps).

**"App won't install"**
- Make sure "Install unknown apps" is enabled for the file manager you used.

**OAuth error / "Access blocked"**
- If you haven't published the app in Google Cloud Console (Step 1, point 5), the OAuth token only lasts 7 days. Publish it to remove this limit.

**Building a new APK after a code change**
- Edit the code in your GitHub fork → push → GitHub Actions rebuilds automatically → download new APK → install (Android will update in place, no need to uninstall).
