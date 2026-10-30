# 手语老师 Sign Hands — project notes for agents

PICO OS 6 spatial app (Spatial SDK 6.1.9, Kotlin/Compose + ECS). Package `io.embodit.signhands`, v0.1.1.
A translucent teacher's hand sits on your own hand and forms the 26 ASL letters; learned letters come back on a
Leitner schedule.

**v0.1.1 (user on the headset: "手势追踪不行，需要模糊一点" / "UI 跟头会偏")**
- Judging is a loose helper, never a gate: for the 15 judged letters only each finger's straight / bent class
  (knuckle-to-tip curl) and the rough palm direction (75 degrees) count, one finger may be off, two close frames in
  one second pass. "Next ›" is always on the follow page; the quiz has no countdown, every letter has "I'm ready"
  and a one-tap "Show me". Unit test: noisy correct hands pass 100 % (3 mm and 6 mm joint noise, 25 degree hand error).
- Panels never follow the head (profile D21): the lesson panel is placed once (0.9 m, 15 degrees down, 14 degrees
  to the non-dominant side, facing you) and stays fixed; pinch-drag the bar under it to move it (it faces you again
  on release); "Bring here" in the title row puts it in front of you again. The demo hand stands at a fixed spot
  beside the panel and moves only with it. The hand label still sits beside your wrist.

Design package (binding): `C:\Users\Admin\Downloads\spatial-app-factory\apps\sign-hands\design-package.json`
(archetype D · one Mixed Stage, Full Space · lesson panel + hand label). PRD / sample links are in the factory
state. Skeleton taken from Moon Phases (same Stage + attachment-panel pool + debug receiver pattern); the hand-
tracking lifecycle follows Spatial Ruler / Access Check.

**Rules:** all 2D UI is SpatialUI (`com.pico.spatial.ui.*`) inside `PicoTheme` (`ui/theme/SignTheme.kt`); no
`androidx.compose.material*` / `MaterialTheme` (gate: `grep -rEf spatial-app-factory/bin/material-grep.txt`).
Do not change the container / window model. Per-frame 3D (hand data, judging, the hands, panels' poses) runs in
`SignScene.onFrame` (one ECS `System`); Compose only gets events and a hint key at <= 10 Hz.

## Layout

| Path | What |
|---|---|
| `domain/` | Pure Kotlin, JVM-tested. `Letters.kt` (asl_letters.json model), `HandFeatures.kt` (hand frame, practice frame, features: flex, spread, thumb tip / hand length, palm normal, pointing; left hands mirrored to right-hand terms), `Matcher.kt` (care items per letter, thresholds, hysteresis 1.0 / 1.3, 0.8 s window at 85 %, worst item, tints, hint hold 1 s), `FakeHand.kt` (debug hand + layout constants), `Progress.kt` (Leitner 1/3/7/14/30), `Flow.kt` (every state S00-S15 + SPIKE, timers, two-step confirm) |
| `data/Store.kt` | `files/progress.json`, `files/settings.json` (clear removes progress only) |
| `platform/` | `HandTracker` (HandTrackingProvider; **not started on the emulator**), `HeadTracker`, `Chime` (system tone, mute in settings) |
| `scene/SignScene.kt` | Teacher's hand (overlay / beside), watch model (2x, 0.65 m, turntable ring drag, slow, from the front, 3/4 default angle, J/Z trail), hint ring / turn-palm arrow, lazy-follow lesson panel, hand label |
| `scene/HandView.kt` | One WebXR generic-hand instance: 6 material slots (thumb..pinky, palm) and the baked clip "poses" scrubbed with `setTime` while paused |
| `ui/` | `SignViewModel`, `SignScreen` (SpatialView + 2 attachment panels, upper limbs Visible), `Copy.kt` (all zh/en copy), `panels/LessonPanel.kt`, `panels/HandLabel.kt` |
| `debug/DebugCommandReceiver.kt` | QA broadcast (debuggable builds only) |
| `tools/bake_hands.py` | Builds `assets/hands/hand_{right,left}.glb` and `assets/asl_letters.json` from the WebXR GLBs + the design package (pose solver = port of the sample's hand.preview.js). Re-run after changing letter poses. |
| `tools/render_check.py` | Blender headless render of baked poses (checks the GLB without a device) |
| `tools/make_icon.py` | 3D launcher icon layers + factory `icon.png` |

Hand model: WebXR Input Profiles generic-hand, MIT, (c) 2019 Amazon — credited in Settings > About;
licence text at `assets/licenses/webxr-input-profiles-LICENSE.md`.

## Debug broadcast

```
adb -s <serial> shell am broadcast -a io.embodit.signhands.DEBUG -p io.embodit.signhands --es cmd "'<command>'"
SERIAL=<serial> bash tools/dev.sh cmd "<command>"
```

| Command | Effect |
|---|---|
| `fakeHand letter=<A-Z/OPEN> [off=thumb/index/middle/ring/pinky/palm] [pos=x,y,z] [bone=0.85-1.15] [jitter=0-1]` | synthetic dominant hand at the teaching spot (practice frame) |
| `noHand` / `lost` | no hand (stays gone) / the fake hand removed (tracking takes over) |
| `letter <L> stage=watch/follow [free=1]` | enter a letter (lesson context, or alphabet "free" mode); follow without a hand shows S02a |
| `watch slow=1 opposite=1 angle=<deg>` | watch toggles; angle adds a drag turn |
| `quiz lesson=<n> index=<i>` | quiz page of a lesson at question i (1-based) |
| `review` | starts a review (seeds three due letters if none) |
| `finishLesson <n> score=<k> [watchOnly=1]` | S11 and its variants |
| `seedProgress` / `clearDue` / `clearData` | lesson 1 done + 3 due today / nothing due / wipe both files (back to S01) |
| `clock +<n>d` | moves "today" |
| `set hand=left/right ghostMode=overlay/side lang=zh/en muted=0/1` | settings |
| `watchOnly 1/0` | watch-only mode |
| `support <SUPPORTED/DEVICE_NOT_SUPPORTED/WITHOUT_PERMISSION/NOT_IN_FULL_SPACE/clear>` | forces the tracking support state |
| `state <S00..S15/SPIKE> [variant=a/b/c/confirm] [hold=1]` | jump to a state; `hold=1` freezes short states (S00, S07, S15) |
| `limbs Visible/Default/Hidden` | upper-limb render mode |
| `recall` / `dragpanel dx=<px> dy=<px>` | "Bring here" / the same path as dragging the panel's bar (`dump` logs `station=(x,y,z) yaw=`) |
| `tap <action>` | panel action without a pointer: tryit nextletter showanswer recall self ready next yes no back home review alphabet settings mute lesson(n=) nextlesson watchagain right left clear confirm slow opposite replay about |
| `release` / `dump` | clears hold / logs scene + state + progress |

Logcat tag `SignHands`. Every state change logs `STATE <id>[/<variant>] letter=<L>`; a judged pass logs `MATCH`;
`AUTOLOG letter=… share=… flex=… spread=… thumb=… wristStepMm=… len=…` is written per judged letter (device data for
the judging templates, design `preconditions.autoLog`).

## Screenshot list (QA)

Emulator: set Graphics to High Definition first; take each shot twice (emu console). Follow shots: send
`letter <L> stage=follow` first, then `fakeHand …`.

| State | Commands |
|---|---|
| S00 | `state S00 hold=1` |
| S01 | `clearData` |
| S02a / S02b | `noHand`, `letter G stage=follow` / `fakeHand letter=G`, `letter G stage=follow`, `noHand` |
| S03a / S03b | `state S03 variant=a` / `state S03 variant=b` |
| S04 | `seedProgress` then `tap home`; `clearDue` for the grey line |
| S05 | `letter G stage=watch`; `watch opposite=1`; `letter A stage=watch` (3/4 view); `watch slow=1`; `letter J stage=watch` (trail) |
| S06 | `fakeHand letter=G`, `letter G stage=follow`, then `fakeHand letter=G off=index` (index coral + ring + hint) and `off=palm` (arrow); `set hand=left ghostMode=side` |
| S07 | `fakeHand letter=G`, `letter G stage=follow hold=1` |
| S08 | `fakeHand letter=M`, `letter M stage=follow`; `letter J stage=follow` |
| S09 | `fakeHand letter=OPEN`, `quiz lesson=1 index=2`; `review` |
| S10 a/b/c | `state S10 variant=a` / `b` / `c` |
| S11 | `finishLesson 2 score=4`; `finishLesson 5 score=6`; `finishLesson 2 watchOnly=1` |
| S12 | `state S12` |
| S13 | `seedProgress`, `tap alphabet` |
| S14 | `tap settings`; `set lang=en`; `state S14 variant=confirm` |
| S15 | `state S15 hold=1` |
| SPIKE | `fakeHand letter=V`, `state SPIKE letter=V` |

## Device-only (not provable on the emulator)

Overlay visibility in the three upper-limb modes, real recognition rates and the judging list, follow latency /
jitter (One-Euro), pinch false triggers, label smoothness, A vs S thumb. The first build is the 5-minute minimal
version (design B16): the user learns lesson 1 and says whether the teacher's hand is readable.
