# Wobble: a launcher only one person asked for

Branch `wobble`. Every app icon (home screen, dock, folder popup, drawer) is a
button pinned to the wallpaper by a red push-pin at its top edge. Tilt the phone and the icons hang
toward the new "down". Jiggle it and they swing, each at its own rate, and then settle.

Nobody needs this, and that is the point. A launcher you rent ships what makes sense for a hundred
million people, so a whim like this never makes the cut. A launcher you own, with an agent that can
read it, takes a whim like this in an afternoon:

| | |
|---|---|
| `Pendulum.java` | the physics: a damped pendulum, pure Java, ~90 lines |
| `Wobble.java` | the Android glue: accelerometer, frame loop, the pin drawable |
| `HomescreenView`, `AppAdapter` | one `Wobble.pin(icon, seed)` line per icon surface (4) |
| `MainActivity` | `Wobble.resume` / `Wobble.pause`, one line each |
| `ids.xml` | one view-tag id |

Everything else in the launcher is untouched, and the generic branch's 796-test suite still passes
alongside 13 new tests for the swing.

## How it behaves

- The accelerometer reports `f = a - g`, so each icon feels `-f`: gravity pulls it down, and jerking
  the phone left throws it right. That's the giggle.
- The pin has a little friction, so on a phone lying flat on a table (with almost no gravity in the
  plane of the screen) the icons drift back upright instead of wandering.
- Each app's swing (length, damping) comes from its package name, so the same app always wobbles
  the same way and its neighbours don't swing in lockstep.
- The frame loop only runs while something is moving, and the sensor is released whenever the
  launcher isn't in front. A still phone costs a sensor subscription and no frames.

## Try it

```
./dev-debug.sh build
```

This installs as **"Wobble Debug"**
(`com.genericlauncher.wobble.debug`), beside the generic "Launcher Debug" rather than over it.
It is a launcher, so there is no icon to open: press Home and pick Wobble Debug. On the emulator, `adb emu sensor set acceleration 6.9:6.9:0`
tilts it 45°.
