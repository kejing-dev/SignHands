# 手语老师 Sign Hands — project notes for agents

PICO OS 6 spatial app (Spatial SDK 6.1.9, Kotlin/Compose + ECS). Package `io.embodit.signhands`, v0.1.4.
A translucent teacher's hand sits on your own hand and forms the 26 ASL letters; learned letters come back on a
Leitner schedule. v0.1.3 adds 59 everyday ASL word signs in 9 themes.

**v0.1.4 (user, 2026-11-02: "UI 窗口下面那个拖动的 bar，别用3D 模型做那么丑的…用那里面带的原生效果的" / "合并到一个模版" /
"用原生 tab bar 组件"; on the headset with v0.1.3, 2026-11-03, twice: "把 UI 的 tab bar 用 spatial UI 的原生组件做" /
"UI window 的 tab bar 还是没有换原生的组件"; profile D8 / D9 / D21, factory `prompts/snippets/native-window-move.md`)**
- All lesson UI is one system Planar window (`Main.kt` `WindowContainer(PANEL_ID, Form.Planar, 620 x 500 dp)`,
  `ui/window/SignWindow.kt`), opened from the Stage. The system moves / closes / minimises it (the dot under it);
  the app has no panel drag code. The self-built 3D drag bar, its collider, `movePanel`, the `dragpanel` command
  and the "捏住下面的横条" line are gone. The turntable ring under the demo hand still drags (the 3D object's own).
- Pages are switched by the SpatialUI `TabBar` (`ui/window/MainWindowTemplate.kt`): 课程 / 字母表 / 常用词 / 设置.
  The page follows the state (`domain/MainWindow.kt` `Tab.of`): a lesson, a free letter or a word demo stays under
  the tab it was started from; tapping the tab already shown does nothing (a stray tap must not end a lesson).
  No TabBar before a hand is chosen (S00 / S01). The ⚙ and "Bring here" left the title row; "Bring here" is a
  settings row now (closes the window and opens it again in front of you, demo hand beside it).
- D9: every open (start, the chip after x, "Bring here") waits until the head is level (|pitch| <= 10 degrees for
  300 ms, `domain/LevelGate.kt`, same as drone-sight v0.1.3); until then the Stage shows "平视前方 / 面板就出来"
  on the line of sight. With the window closed (x) the Stage shows an "打开面板" chip. One process-wide
  `SignViewModel.shared()` serves the Stage and the window (closing the window keeps the lesson).
- D8: the app cannot read the window's pose; it estimates it when the window is created (straight ahead of the
  level head, 1.75 m). The demo hand stands on the dominant side of it, 0.8 m ahead, 0.48 m out, 0.18 m down
  (about 31 degrees), never in front of or behind it; the hand label is pushed off it (`MainWindowLayout.clearOf`).
  After a system drag the estimate is stale until "Bring here".

**v0.1.3 (user on the headset with v0.1.2: "握拳 M N，移动的比如 J 需要更模糊的判定正确，不然太难一直不对" /
"需要更多词的…把常用的日常英语的词都加了" / "在设置里也加个退出应用的按钮"; profile D27, B18, D26)**
- Forgiving judging (`domain/Matcher.kt`, `domain/Trajectory.kt`): every letter is judged now (no more self-check).
  Each target has its own items: per finger straight (curl <= 55) / bent (>= 60) / curved (template +- 45), one finger
  may be off, palm within 75 degrees for still letters. Fist letters A E M N S T compare only the fingers folded plus
  where the thumb is: A beside, S across, E / M / N / T tucked (M, N, T are not told apart - the tracker cannot see a
  thumb under the fingers). A still letter passes when held 0.3 s (60 % of the frames of the last 0.5 s close).
  J, Z and the word signs compare the path shape only: the followed point (pinky tip / index tip / palm centre, head
  frame) is smoothed, resampled by arc length, centred and scaled, then compared by banded RMS DTW (threshold 0.15),
  with the template turned +-25 degrees in the facing plane and +-30 degrees about the vertical; windows of
  0.3 - 3.2 s are tried (twice as slow fits); word signs must start within 0.2 m of their spot. Two "almost" tries
  (1.5 s each, best ratio <= 1.6) in a row loosen the target one step (x1.3, then x1.6); the label says
  "放宽了一点，再来" and always says what is off (finger / thumb / palm / "make the move bigger" / "follow the path" /
  "a bit higher" ...). "Next ›" is always on the follow page.
- Sloppy-recording gate (`SloppyRecordingsTest`): 12 synthetic recordings (turned 15-20 degrees, half to the side
  25-30 degrees, twice as slow, 10 cm off, 3 mm jitter; M N T A J J Z THANK_YOU HELLO MOTHER PLEASE WHERE, one left
  hand) must all pass on the first try without loosening; written to `app/build/sloppy-recordings/`. Synthetic
  because no headset was on the build machine: replace / add real recordings when the headset logs them
  (`AUTOLOG` / `MATCH` lines carry tier and move ratio).
- Everyday words (`domain/Signs.kt`): 59 signs, themes greetings 6, polite words 5, yes / no 5, family 7, food & drink 7,
  time 7, feelings 6, verbs 10, questions 6. Each = a handshape the hand GLB already has (letter pose or OPEN), a palm
  orientation, a start spot (head frame) and a palm path; two-handed signs show the other hand resting (demo only, not
  judged). Home > "常用词" opens the words page (S16, "‹ theme ›" + 3-column word cells, ✓ when done); a word opens
  its demo (S05: the big hand at 1.3x moves round a head ring + shoulder line with the path drawn), "Try it" (S06,
  judged), then the theme's next word; back returns to the words page.
  **Word list source / licence:** the list is the everyday vocabulary taught first in beginner ASL courses; the
  handshape / spot / path data and the Chinese and English descriptions were written for this app from general ASL
  knowledge and simplified to one hand path. No dictionary text, drawing or video was copied (nothing to license).
  The descriptions were not checked sign by sign against a dictionary on the build machine: a Deaf / ASL-fluent review
  is still due (QA / user).
- Settings > "退出应用" (last row) > "退出手语老师？" confirm > the scene detaches (hand + head tracking stop, entities
  and panels go), every app task is finished and removed, the process ends 0.3 s later. About now replaces the
  three setting rows instead of pushing the page out.
- Panel placement (profile D9): start-up, the 70-degree re-place and "Bring here" all go through
  `Placement.placeUpright` (heading only, upright, 15 degrees below the eye line, 0.9 m, 14 degrees to the
  non-dominant side); `PlacementTest` covers head down 30 / up 30 / tilted 20.

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
| `domain/` | Pure Kotlin, JVM-tested. `Letters.kt` (asl_letters.json model), `HandFeatures.kt` (hand frame, practice frame, features: flex, spread, thumb tip / hand length, palm normal, pointing, `trackPoint`; left hands mirrored to right-hand terms), `Matcher.kt` (v0.1.3 targets, per-target care items, hold 0.3 s, path judging, tiers, hints), `Trajectory.kt` (resample / normalize / banded RMS DTW), `Signs.kt` (59 word signs, themes, palm orientations, body spots), `Placement.kt` (D9 upright placement), `MainWindow.kt` (v0.1.4: `Tab`, `MainWindowLayout` window estimate / demo spot / label clearance, `LevelHintPlacement`), `LevelGate.kt` (open the window only with a level head), `FakeHand.kt` (debug hand, layout constants, J / Z paths, `Performer` = synthetic person doing a target, `Sloppy`), `Progress.kt` (Leitner 1/3/7/14/30), `Flow.kt` (every state S00-S16 + SPIKE, timers, two-step confirm, exit) |
| `data/Store.kt` | `files/progress.json`, `files/settings.json` (clear removes progress only) |
| `platform/` | `HandTracker` (HandTrackingProvider; **not started on the emulator**), `HeadTracker`, `Chime` (system tone, mute in settings) |
| `scene/SignScene.kt` | Teacher's hand (overlay / beside), watch model (2x, 0.65 m, turntable ring drag, slow, from the front, 3/4 default angle, J/Z trail), hint ring / turn-palm arrow, hand label, the window chip / level hint; steps the window's level gate; keeps 3D content off the window's estimated rect |
| `scene/HandView.kt` | One WebXR generic-hand instance: 6 material slots (thumb..pinky, palm) and the baked clip "poses" scrubbed with `setTime` while paused |
| `ui/` | `SignViewModel` (process-wide `shared()`), `SignScreen` (Stage: SpatialView + hand label + window chip, upper limbs Visible, opens the window), `window/` (`SignWindow` = the system main window, `MainWindowTemplate` = native TabBar, `WindowCtl` = open / recall / lifecycle / level gate), `Copy.kt` (all zh/en copy), `panels/LessonPanel.kt`, `panels/HandLabel.kt` |
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
| `fakeHand sign=<ID> [slow=2] [turn=<deg>] [pos=x,y,z]` / `fakeHand letter=J move=1 …` | v0.1.3: the debug hand performs a word sign / draws J or Z over and over (path judging on the emulator; `pos` is the head-frame offset) |
| `words [theme=GREET/POLITE/YESNO/FAMILY/FOOD/TIME/FEEL/VERB/QUESTION]` | words page (S16) on a theme |
| `sign <ID> [stage=follow]` | a word's demo (S05) / its judged follow page (S06) |
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
| `recall` | "Bring here" (settings row): close the window, reopen it once the head is level (`dump` logs the window estimate `station=`) |
| `window open/close/chip` | v0.1.4: ask for the window (level gate) / close it like the system x / the Stage chip's path |
| `tab lessons/alphabet/words/settings` | v0.1.4: the same events as the TabBar items |
| `tap <action>` | panel action without a pointer: tryit nextletter showanswer recall self ready next yes no back home review alphabet settings mute lesson(n=) nextlesson watchagain right left clear confirm slow opposite replay about; v0.1.3: words word(id=) themenext themeprev exit exitcancel exitconfirm (exitconfirm really exits) |
| `release` / `dump` | clears hold / logs scene + state + progress |

Logcat tag `SignHands`. Every state change logs `STATE <id>[/<variant>] letter=<L>`; a judged pass logs
`MATCH target=<L/ID> tier=<0-2> move=<ratio>`; a loosening logs `LOOSEN target= tier=`; text that does not fit logs
`OVERFLOW <panel>/<control> '<text>'`; exit logs `EXIT confirmed` / `EXIT tasks finished`;
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
| S16 (v0.1.3) | `words theme=GREET`; `words theme=VERB` (10 words, the fullest page); `set lang=en`, same two |
| Word demo (v0.1.3) | `sign THANK_YOU` (one hand, chin); `sign NICE` (two hands); `watch opposite=1`; `watch angle=90` |
| Word follow (v0.1.3) | `fakeHand sign=HELLO`, `sign HELLO stage=follow` (MATCH within ~1 s); `fakeHand sign=MOTHER pos=0,-0.3,0`, `sign MOTHER stage=follow` (label "手再高一点") |
| J / Z judged (v0.1.3) | `fakeHand letter=J move=1 slow=2`, `letter J stage=follow` (MATCH); `fakeHand letter=M`, `letter M stage=follow` (MATCH) |
| Main window (v0.1.4) | first frame: system window + TabBar above it + system dot below; hover the dot (x / drag / -); hover the TabBar (labels); `tab alphabet` / `tab words` / `tab settings` |
| Window x / chip (v0.1.4) | `window close` (Stage shows "打开面板"), `window chip` (window back in front) |
| Bring here (v0.1.4) | drag the window away with the system dot, then `recall`: log `ON_DESTROY` -> `LEVEL ok` -> `ON_CREATE` -> `WINDOW estimate` |
| D9 (v0.1.4) | cold start with the emulator camera pitched -30 / +30 (right-drag 801 fb px = 30 degrees): no window, "Look straight ahead" on the line of sight; level the camera -> the window opens upright |
| S14 exit (v0.1.3) | `tap settings` (exit row at the bottom); `tap exit` (confirm page); `tap exitcancel`; `tap about` (about replaces the rows) |

## Device-only (not provable on the emulator)

Overlay visibility in the three upper-limb modes, real recognition rates and the judging list, follow latency /
jitter (One-Euro), pinch false triggers, label smoothness, A vs S thumb. The first build is the 5-minute minimal
version (design B16): the user learns lesson 1 and says whether the teacher's hand is readable.
