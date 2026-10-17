# MG4 Radio

An Android Auto media app for the MG4's own radio. Pick it in Android Auto like Spotify or Amazon Music and you get the standard Android Auto player for the car's DAB/FM tuner: station name, now-playing text, real station logos, play/pause and next/previous, and the full station list with logos.

Audio stays on the car's radio. The phone app only shows the player and sends commands.

## How it works

The phone talks to [DiAuto-MG4](https://github.com/merthankaraman/DiAuto-MG4) on the head unit, which exposes the car radio (SAIC's radio service, the same one the home-screen radio widget uses) over a small HTTP bridge on the car's hotspot (port 30519). That bridge is in the `feat/mg4-radio-bridge` pull request.

- Next/previous and play/pause behave like the radio widget on the car's home screen
- Station logos come from TuneIn, with radio-browser.info as a fallback (BBC stations), then the car's own DAB logo, then a generated tile. They are cached on the phone.
- The station playing and the ones you played recently are listed first, which also drives Android Auto's "For you" card

## Requirements

- MG4 running DiAuto-MG4 with the radio bridge, connected by wireless Android Auto (the phone on the car's hotspot)
- Android Auto with developer settings → **Unknown sources** on (the app isn't on the Play Store)
- If you run a VPN that doesn't allow bypass (Tailscale, for example), exclude MG4 Radio from it, or it can't reach the car

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The instrumented test drives the app the way Android Auto does against a fake bridge: run `python3 tools/fake_bridge.py` on your machine, then `./gradlew connectedDebugAndroidTest -PradioHost=<your machine's IP>`.

The launcher icon is a plain vector. To use the car's own radio icon instead, drop it in as `app/src/main/res/drawable-nodpi/ic_launcher_radio.png` (git-ignored, not redistributable) and point `android:icon` at it.
