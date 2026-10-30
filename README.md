# Pedal Remote

Edit the current preset on an IK Multimedia **ToneX One** from an Android phone over a USB-C cable.
Block on/off (Gate, Comp, Amp, Mod, Delay, Reverb), amp (gain, model volume, mix, presence, depth),
EQ (with frequencies and Q), compressor and reverb.

## Install on the phone

Download the newest build on the phone and open it:

**https://github.com/reyffer/pedal-remote/releases/latest/download/pedal-remote.apk**

Android will ask to allow installing from your browser the first time ("Install unknown apps").
New versions install over the old one.

## Use

1. Plug the ToneX One into the phone (USB-C to USB-C).
2. Android asks whether to open Pedal Remote. Tick "Always" and tap OK. (Or open the app and tap Connect.)
3. The current preset loads. Changes are sent to the pedal immediately, and the ToneX One keeps them
   (it auto-saves; there is no separate save step). **Undo changes** puts the preset back to how it was
   when the app first read it.

Back up your presets in ToneX Editor before first use.

## How it works

- `app/src/main/assets/index.html` is the whole screen and the pedal protocol. It also runs in Chrome/Edge
  on a laptop (Web Serial), and has a demo mode (`index.html?demo`).
- `MainActivity.java` is a thin wrapper. It exists because on many Android phones the system's own
  USB-serial driver grabs the pedal and Chrome can't take it back; a normal app can ("force claim").
- The app only sends read requests and single-parameter changes. It never sends the pedal's global
  state block, so it can't change slots, colours or global settings.
- Each push to `main` builds a signed APK with GitHub Actions and publishes it as a release.

## Credits

Protocol knowledge from the open-source [TonexOneController](https://github.com/Builty/TonexOneController)
by Greg Smith (Apache 2.0) and [vit3k/tonex_controller](https://github.com/vit3k/tonex_controller).
Unofficial; not made or endorsed by IK Multimedia.
