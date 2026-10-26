# Dance Steps (舞步地图)

Ballroom / Latin basic-step trainer for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full
Space, passthrough), package `io.embodit.dancesteps`. The footprints of a basic step (waltz box, rumba box,
salsa basic, cha-cha basic) lie 1:1 on your own floor and light up on the beat of synthesised music; an
eye-level "mirror" panel ahead of the dance floor shows a top-down map with ghost feet and your position
so you never look down; your head's floor point draws your route, and the run ends with one verdict.

Design package (binding): `../spatial-app-factory/apps/dance-steps/design-package.json` (archetype D,
container STAGE_MIXED, window model single_panel_with_popup, 18 states, patterns, rules, copy). Mockup:
`../spatial-app-factory/apps/dance-steps/sample.html`. Reference project (same skeleton): `../spatial-ruler`
(its `AGENTS.md` holds the SDK findings this app relies on); the platform and panel-pool code was taken from
`../fire-plan`, which took it from the ruler.

Built by the factory's build station on 2026-10-25 (v0.1.0, debug); self-tested on emulator-5560
(Pico_Emulator_6_1_fresh) on 2026-10-26: two clean launches without an ANR (scene built in 13.8 s), every
designed state captured (`../spatial-app-factory/apps/dance-steps/build/shots/`), 30 panel show / hide cycles
without black glass, the debug walkers verified against the score rule. Not yet run on a headset.

## Emulator findings (2026-10-26)

- Start-up: every mesh is built in `DanceScene.build` through `pacedMesh` (a mesh that took > 60 ms on the
  main thread is followed by a 1.2 s pause; the idle emulator takes about a second per mesh and several in a
  row raise an ANR). Boxes are a MeshModel unit cube scaled per entity: `MeshResource.createBox` draws about
  twice the size on the emulator.
- The idle emulator runs at 1 fps, so the live trail gets one sample a second there (10 Hz on a headset); the
  debug walkers fill their samples at beat-time resolution so T10 can be checked on the emulator.
- The Stage origin is at the head, the floor at y = 0.40 and the head at 1.65: the dance floor placed under the
  head is below the frame; pitch the camera down (`tools/pitch.sh 50`, camera_rotation_button must be On) to
  photograph the tape, the orange crowded edge and the footprints; `tools/pitch.sh -50` turns back.
- HOME opened the "Back to Home" dialog only once in a boot (it shows the app icon: launcher evidence); the
  launcher grid cannot be brought forward while the Stage is open. `adb exec-out screencap` gives half frames at
  1 fps; the console screenshot (`emu screenrecord screenshot`) is whole.
- After `adb install -r` the old Stage lingers in full space and a relaunch is refused ("current full space
  don't allow start fullscreen"): `am force-stop` first, then launch.

## Structure

```
app/src/main/java/io/embodit/dancesteps/
  Main.kt                      DefaultStage { DanceStepsTheme { DanceScreen() } }
  platform/                    SpatialApplication, LaunchActivity, DanceGraph (wiring), DanceLog
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres)            [from Spatial Ruler]
    tracking/HeadTracker       HMD pose, lost-tracking sign, block reason                [from Spatial Ruler]
    input/ControllerInput      controller ray, trigger, thumbstick                       [from Fire Plan]
    audio/BeatEngine           the music + beat clock: one streaming AudioTrack, synthesised voices
  domain/model                 Vec3 / Quat4, PlaneSurface, DanceModel (Role, Speed, Verdict, Pattern, Step,
                               Settings, DanceRules = every number of the design), Patterns (the four step tables)
  domain/usecase               CenterPathUseCase (expected centre of mass over a loop), ScoreUseCase (the verdict),
                               PlaceFloorUseCase (FloorPose, cramped / front-wall check), HitSurface, ViewFrame,
                               FacePanel, RestBody                                        [last four from Spatial Ruler]
  data/DanceRepository         SharedPreferences JSON: last pattern, role, speed per pattern, language, coach seen,
                               last verdict per pattern x speed
  ui/theme                     DanceColors (the only colour literals), DanceStepsTheme (PicoTheme), typography (Noto Sans
                               + Noto Sans SC subset)
  ui/dance                     DanceViewModel (the state machine), DanceUiState / DanceEvent, MapSnapshot, DanceScreen
  ui/dance/components          DanceStage (SpatialView + panel pool), MirrorPanel (7 layouts), MirrorMap (Canvas map +
                               pattern thumbnails), MoreMenu, Overlays (dialog, snackbar, coach), Common, DanceStrings
  ui/dance/scene               DanceScene (ECS: one frame System; follow, placing, beat timeline, trail), FloorViews
                               (DanceFloorView tape frame, FootprintView, PatternView, YouView), PanelRig (pooled
                               attachment panels), Follower (lazy follow), PlaneColliders, PanelSpec, SceneKit
  ui/dance/debug/DanceProbe    seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
tools/                         subset_cjk_font.py (re-run when zh strings change), make_icon.py (512 export)
```

One direction of data: panel controls, scene callbacks (planes, tracking, head ten times a second, beat
edges, run results) and debug hooks -> `DanceViewModel.onEvent` -> `DanceUiState` -> `DanceScene.render`
(idempotent diff of entities, arms / silences the audio) and the panels. Per-frame work (floor following,
footprint levels from the beat clock, the spreading beat ring, your dot and trail, the mirror's satellites)
lives in `DanceScene.onFrame` only. The mirror's map reads `scene.map` (a 10 Hz snapshot), not the state.

## The beat clock

`BeatEngine` writes 10 ms blocks into one streaming 48 kHz stereo `AudioTrack` from its own thread.
`arm()` fixes beat 0 at write-frame + 120 ms; the schedule is `countIn` metronome beats (one bar) and then
`loops` loops of music in the pattern's style (waltz oom-pah-pah, rumba shaker / conga, salsa cowbell /
clave / tumbao, cha-cha cowbell / guiro). `beatPosition()` = (frame being played - anchor) / frames per
beat, the played frame from `getTimestamp` extrapolation (fallback: playback head - 80 ms). The scene reads
it every frame: `pos < countIn` is the count-in; after that `loop = startLoop + floor(beat / loopBeats)`.
Nothing short is created per beat (the knowledge base notes frequent short native tracks drag frames).
Pause = silence in the stream; background = `stop()` (pause + flush), foreground = a fresh stream.

## Floor, pattern and mirror

- Floor frame: 1.5 x 1.5 m, eight 120 mm tape dashes per edge + a chevron at the front; 60 % while placing,
  35 % once placed; the crowded edge turns alert orange. Placing: centre = head floor point (0.3 s smooth),
  front = head heading; the controller thumbstick turns it 60 degrees per second (dead zone 0.2) and within
  8 degrees of a nearby wall it snaps parallel (taste rule D1; log `SNAP on|off`). "Place here" is disabled
  while a front wall is nearer than 1.35 m to the centre (`PlaceFloorUseCase`).
- Pattern coordinates: x right, z ahead, origin at the middle of the start stance; the pattern's bounding
  box is centred on the floor (`Pattern.boundsCenter`). Follower = (x, z) -> (-x, -z) with feet swapped.
- Footprints: forefoot 100 x 140 + heel 70 x 80 mm ellipses (unit disc / ring meshes scaled), 2 mm up.
  Levels: outline 25 % (future), fill 55 % (next, one step ahead), 100 % (current, with a ring spreading to
  1.6x in 0.25 s), 15 % (done this loop). Start stance = two 70 % outlines breathing at 1 Hz (2.5 Hz while
  "stand on the flashing footprints").
- Route: the expected centre of mass (0.7 bearing foot + 0.3 other, half-beat smoothstep shift) as 50 / 40 mm
  gold dashes; arrow from the current to the next footprint (none for an in-place step).
- You: 120 mm white disc + interaction ring at the head's floor point (0.1 s smoothing); trail = 12 mm strips
  every 100 ms, last 160 while dancing in four fade tiers (retuned every 0.5 s), the whole run (<= 400) in
  the summary. A paused loop's samples and trail are dropped; resume restarts that loop after a count-in.
- Mirror (640 x 520 dp, scale 1 = 0.512 x 0.416 m): lazy follow 1.1 m ahead / 10 degrees down until the floor
  is placed; then fixed 1.25 m ahead of the floor centre, 0.10 m below the head height at placing, facing the
  floor, leaning back 5 degrees. Snackbar 0.04 m above its top edge; menu under the More key; coach bubble to
  its left; the unavailable dialog 0.9 m ahead of the head.
- Score (`ScoreUseCase`): moved-or-not ratio (RMS of you / RMS of the expected centre) < 0.5 -> "bigger steps";
  else mean distance to the expected centre within a quarter beat: <= 6 cm steady, <= 12 ok, else slower.
  A Python replay of the four debug walkers over all patterns gives steady / steady / slower / bigger as the
  design asks (T10). Posture: the head's mean offset over the start stance during the count-in, capped 12 cm,
  is subtracted first.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels declared once with fixed sizes: `mirror` 640 x 520, `moreMenu` 256 x 184, `snackbar`
520 x 72 (capsule hugs its text), `blockedDialog` 480 x 280, `coach` 360 x 96. Dialog, snackbar and coachmark
are self-built (`AlertDialog` / `SnackbarHost` / `Menu` do nothing or lose pinches in a Stage). The count
numeral is 120 sp tabular; rumba counts S / Q are shown as 慢 / 快 (zh) or S / Q (en). Copy: `res/values/strings.xml`
(en) and `res/values-zh`, read through `rs()` so the in-app language switch works without a restart. No count
voice ships (no licensed samples), so the menu has no voice switch (design `audio.voice`).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.dancesteps.DEBUG -p io.embodit.dancesteps --es cmd '<command>'"
```

| command | effect |
|---|---|
| `state <name>` | straight to a designed state: `starting`, `picker`, `placing`, `ready`, `countIn` (the count-in is stretched to 60 beats so that it can be photographed), `dancing`, `paused`, `summary`, `blocked`; overlays after `+`: `scanning`, `cramped`, `front` (front too close), `notOnStart`, `preview`, `offFloor`, `lost`, `menu`, `coach`; `:follower` at the end selects the follower steps (without it the leader). A forced overlay is held against live sensing until the next `state`. States that need a floor place it under the head first. Example: `state dancing+offFloor:follower` |
| `place` | puts the floor under the head now (as "place here") |
| `pattern waltzBox|rumbaBox|salsaBasic|chachaBasic`, `role leader|follower`, `speed slow|mid|full` | as the controls |
| `start`, `pause`, `resume`, `restart`, `end`, `preview`, `again`, `faster`, `back`, `menu`, `replace`, `exit` | as the controls |
| `walk_late` / `walk_noisy` / `walk_offset` / `stand_still` | a synthetic dancer runs the 8 loops (T10: steady / at least ok / slower / bigger); `walk off` stops |
| `walk_x` | the you-dot walks +x 0.5 m over 2 s: the map's dot must move right |
| `lost` / `found` | tracking lost forced / released (+lost) |
| `coach_reset` | the coachmark shows again |
| `summary steady|ok|slower|bigger [cm]` | the summary with that verdict |
| `lang system|zh|en` | language |
| `ui <control>` | presses a panel control by name: `pattern_<id>`, `role_leader`, `role_follower`, `speed_slow|mid|full`, `place_here`, `back`, `more`, `start`, `preview`, `pause`, `resume`, `restart`, `end_early`, `again`, `faster`, `change_pattern`, `menu_replace`, `menu_exit`, `lang_system|zh|en`, `coach_got_it`, `dialog_exit`, `dialog_retry` |
| `dump` | `DUMP {json}` (also files/dump.json): state name, phase, pattern, role, speed, bpm, floor, run, summary, you |
| `pose`, `bounds`, `planes`, `park`, `fps on|off` | scene queries (`pose` includes the beat position) |

Screenshot list for QA (design `states[].qaCapture`, name = phase[+overlay][:mode]): `starting`, `picker`,
`picker+scanning`, `placing`, `placing+cramped`, `placing+front`, `ready`, `ready+coach`, `ready+notOnStart`,
`ready+preview` (short: capture within 3 s), `countIn`, `dancing`, `dancing+offFloor`, `paused`, `paused+lost`,
`summary`, `blocked`, `picker+menu`, `ready:follower`. `tools/qa_all.sh [OUT_DIR]` takes all of them;
`tools/qa_shot.sh "<state>" [file]` one (sends the state, `fast on`, settles, two console screenshots). An idle
emulator runs at about 1 fps, so give a freshly shown panel a few frames.

Log tag `DanceSteps`: `STATE <name> phase=…`, `EVENT`, `PLANES`, `FLOOR placed`, `PLACE check`, `SNAP`,
`MIRROR fixed`, `RUN start|pause|complete|finish`, `AUDIO start|arm|silence|stop`, `TRACK`, `COACH`, `TAP`,
`CMD`, `DUMP`, `POSE`, `PANEL`, `FPS`.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.dancesteps -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src`
must print nothing.
