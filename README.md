<p align="center"><img src="docs/icon.png" width="128" alt="Dance Steps icon"></p>

# Dance Steps | 舞步地图

**交谊舞 / 拉丁舞基本步的脚印 1:1 铺在你家地板上跟拍子亮，视线高度的镜子面板告诉你下一步，跳完画出你的路线。**

**The footprints of a ballroom / Latin basic step lie life-size on your own floor and light up on the beat; an eye-level "mirror" panel shows the next step so you never look down, and at the end it draws the route you danced.**

PICO OS 6 · PICO Spatial SDK 6.1.9 · Kotlin / SpatialUI · package `io.embodit.dancesteps` · v0.1.0 (pre-release / 预发布)

> **模拟器 QA 通过，真机未验证（Simulator-QA passed; not yet verified on a real headset）。**

![封面：低头看地板，华尔兹方步的脚印、金色路线、你的白点和白色轨迹](docs/cover-dancing-floor.jpg)

> 截图和演示视频来自原生模拟器（PICO Emulator 6.1），由调试广播驱动跳转到各设计状态，跳舞的人是程序合成的「合成舞者」；**非真实手势录像**。
> Screenshots and the demo video were taken on the native PICO emulator, driven by debug broadcasts; the dancer is a synthetic debug walker; **not a recording of real hand gestures or a real person.**
>
> 音乐和拍点由程序合成，没有数拍人声。
> The music and beat clicks are synthesised in code; there is no counting voice.

---

## 中文

### 这是什么

选一个步型：华尔兹方步、伦巴方步、萨尔萨基本步、恰恰基本步（原地三步版），领舞步或跟舞步。在空地上摆一个 1.5×1.5 米的舞池（舞池朝向在 8° 以内会自动贴平最近的墙），地板上就铺出 1:1 的脚印和金色路线。

开始后先数一小节预备拍，然后脚印跟着拍子亮：当前这一步全亮，下一步半亮，中间有箭头。舞池前方视线高度立着一块「镜子」面板，小地图上有幽灵脚和你的位置，大数字显示拍子和「第 n 遍 / 8」，不用低头看脚。你的头在地面上的投影画出白色轨迹；跳满 8 遍后，地上和小地图只剩标准路线和你整次的轨迹，再给一句结论（很稳 / 还行 / 再慢一点 / 步子再大一点）和平均偏差。速度分慢 · 中 · 快，可以先「看一遍」。中英双语，全部本机保存，不联网。

### 安装与启动

```bash
adb install -r DanceSteps-v0.1.0.apk
pico-cli app launch io.embodit.dancesteps
```

APK 在 [Releases](https://github.com/kejing-dev/DanceSteps/releases) 页面下载（仓库不含 APK）。需要 PICO OS 6 头显或 PICO Emulator 6.1；用到地面识别和头部位置。APK 约 34 MB。

### 容器与骨架

- **容器**：只有 Full Space · `STAGE_MIXED`（透视），没有 Shared Space 版本。脚印要 1:1 铺在真实地板上，你的位置来自头部在地面上的投影，地面识别和头部位姿只在 Full Space 可用。
- **骨架 D**（同火情预案 Fire Plan / 空间尺 Spatial Ruler）：平面识别、头部位姿、懒跟随面板、面板池、摇杆输入。开场的 3D 网格排队分帧生成，避免主线程卡住；音乐和节拍器在应用里实时合成。
- **状态**：设计包 18 个状态（9 个阶段 + 叠加 / 模式），全部可由调试广播直接跳转并截图，结构见 [AGENTS.md](AGENTS.md)。

### 截图

| | |
|---|---|
| ![选步型：四个步型，领舞步 / 跟舞步](docs/picker-mirror.jpg) | ![摆舞池：地上白色虚线方框和淡淡的脚印](docs/placing-floor.jpg) |
| 选步型 — 华尔兹 / 伦巴 / 萨尔萨 / 恰恰 | 摆舞池 — 白色虚线方框跟着你走 |
| ![跳舞中：镜子小地图和大数字 3](docs/dancing-mirror.jpg) | ![跳舞中：地上当前脚印亮、下一步半亮](docs/dancing-floor.jpg) |
| 镜子面板 — 幽灵脚、大数字拍子、第 2 遍 / 8 | 地板 — 当前脚印亮，下一步半亮 + 箭头 |
| ![结果：很稳 · 平均偏 5 cm](docs/summary-mirror.jpg) | ![结果：地上的标准路线和整次轨迹](docs/summary-floor.jpg) |
| 结果 — 「很稳 · 平均偏 5 cm · 慢 · 8 遍」 | 结果 — 标准路线 + 你整次的轨迹 |

启动器图标：

<img src="docs/launcher.jpg" width="480" alt="系统「回到主页」框里的应用图标">

### 已知问题（QA 第 1 轮，不影响使用）

- 摆舞池碰到墙或家具时，镜子上多出的那行橙色提示字在浅色玻璃上太淡，几乎看不清；橙色边框照常亮。
- 镜子右栏的小字（领舞步 / 跟舞步、慢 · 中 · 快、BPM）离 1.5 米看偏小；大数字和小地图清楚。
- 空闲模拟器上首次打开约 18 秒才到选步型：3D 模型排队生成（防「没有响应」），头显上会快很多。
- 选步型列表里的「上次结果」在默认距离看不清具体字。
- 没有数拍人声：没有授权干净的人声采样，按设计不做，菜单里也没有这个开关。

还没在头显上验过：真人踩脚印（模拟器里用合成舞者和调试移动代替）、音乐和拍子听起来对不对、手柄摇杆转舞池、捏合点按钮的手感、真实房间里地面识别的速度和碰到家具的提示是否准确。

---

## English

### What it is

Pick a step: waltz box, rumba box, salsa basic or cha-cha basic (in-place triple version), as leader or follower. Place a 1.5 × 1.5 m dance floor in a clear spot (it snaps square to the nearest wall within 8°) and the life-size footprints and a gold route appear on your floor.

After one bar of count-in the footprints light up on the beat: the current step fully lit, the next one half lit with an arrow between them. An eye-level "mirror" panel stands ahead of the dance floor with a mini-map of ghost feet and your position, a big beat number and the repetition count out of 8, so you never have to look down. Your head's floor point draws a white trail; after 8 repetitions only the reference route and your whole trail remain, with one verdict (steady / okay / slow down / bigger steps) and the average offset. Slow, medium and fast tempos, plus a preview run. Chinese and English; everything stored on the device, no network.

### Install and launch

```bash
adb install -r DanceSteps-v0.1.0.apk
pico-cli app launch io.embodit.dancesteps
```

Download the APK from the [Releases](https://github.com/kejing-dev/DanceSteps/releases) page (the repository itself does not ship an APK). Needs a PICO OS 6 headset or PICO Emulator 6.1; uses floor detection and head pose. The APK is about 34 MB.

### Container and skeleton

- **Container**: Full Space only, `STAGE_MIXED` (passthrough); no Shared Space version. Footprints must lie life-size on the real floor and your position comes from your head's floor point; floor detection and head pose are Full Space only.
- **Skeleton D** (same as Fire Plan / Spatial Ruler): plane detection, head pose, lazy-follow panel, panel pool, thumbstick input. Startup 3D meshes are queued across frames so the main thread never stalls; music and the metronome are synthesised live in the app.
- **States**: 18 designed states (9 phases plus overlays / modes), each reachable by a debug broadcast and captured; see [AGENTS.md](AGENTS.md).

### Screenshots

See the table above: step picker, placing the floor, the mirror while dancing, the lit footprints, and the result on the mirror and on the floor.

### Known issues (QA round 1, none blocking)

- When the dance floor touches a wall or furniture, the extra orange hint line on the mirror is too faint on the light glass; the orange edge still lights up.
- The small text in the mirror's right column (leader / follower, tempo, BPM) is small at 1.5 m; the big beat number and the mini-map read fine.
- On an idle emulator the step picker takes about 18 s to appear on first launch, because startup meshes are queued; a headset is far faster.
- The "last result" line in the step list cannot be read at the default distance.
- No counting voice: there is no cleanly licensed voice sample, so by design it is left out and the menu has no switch for it.

Not yet verified on a headset: a real person stepping on the footprints (the emulator used a synthetic walker and debug moves), whether the music and beat sound right, thumbstick rotation of the floor, the feel of pinching buttons, floor detection speed and furniture hints in a real room.

---

## License

Source code © 2026 Embodit. All rights reserved (private repository). Bundled fonts Noto Sans / Noto Sans SC (subset) are under the SIL Open Font License, see `third_party/`. Music and beat clicks are generated in code; no third-party audio.
