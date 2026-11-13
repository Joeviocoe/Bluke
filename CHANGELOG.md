# Changelog

All notable user-facing changes and improvements to Bluke are documented in this file.
For detailed usage instructions, see the [Usage Guide](docs/USAGE.md).

## [Unreleased]

## [1.2.0] - 2026-11-13

### Added
- **Phone Keyboard Mode:** Type on the host with the phone's own keyboard app, in one of two styles:
  - **Text box:** a multi-line box with autocorrect, selection, copy and paste. Auto-type sends changes as you make them, or turn it off and press Type. Erase backspaces whatever the box typed on the host; Clear empties the box.
  - **Direct:** keys go straight to the host as raw keystrokes, with no text on the phone that could fall out of step with the host's text or cursor. A screenkey-style line shows the keystrokes as they are sent.

  The arrow keys sit together as their own block.
- **Configurable Extra Keys:** Arrange rows of keys a phone keyboard lacks (Ctrl, Alt, Shift and Super as tap-to-latch modifiers, Esc, Tab, arrows, F-keys, symbols, host shortcuts) under Settings > Keyboard > Phone Keyboard.
- **Host Keyboard Layouts & Unicode Entry:** Phone Keyboard and clipboard typing translate characters for the computer's layout (US, UK, German, Hungarian, French, Italian, Spanish, Swedish, Norwegian, Danish), with optional Linux, Windows and macOS Unicode entry for characters a layout cannot type.
- **Multimedia Long-Press Keys:** Tap **B** to blank a slideshow to black, or long-press it for white (**W**). Tap **Esc** as before, or long-press it for **F** (fullscreen in YouTube, Netflix and VLC). Each button shows its long-press key in small type underneath.
- **Multimedia Speed and Frame Keys:** Small keys fill the corners around the arrow pad: playback speed (tap **< >** for YouTube, long-press **[ ]** for VLC and mpv) above, and **, .** frame-step below.
- **Fullscreen Toggle:** Settings > Look & Feel can keep the status and navigation bars visible while an input mode is open (fullscreen stays the default).
- **Bluetooth LE keyboard (HID over GATT):** Settings > Behavior can additionally advertise Bluke as a Bluetooth LE keyboard. Hosts that connect over classic Bluetooth but ignore the input, notably iPad and iPhone before iOS 13, type over BLE (verified on an iPad 3 with iOS 9.3.5). The host pairs from its own Bluetooth settings and the Home screen shows the BLE state. The BLE link is keyboard-only; a developer option enables the experimental composite map with trackpad, controller and media keys.

### Changed
- **Input Mode Dropdown:** The mode button opens a list of your enabled modes instead of cycling to the next one.
- **Multimedia Trackpad Hidden by Default:** The trackpad and mouse buttons now sit behind a toolbar toggle, and the remote buttons grow to fill the space.
- **Bigger, Colour-Coded Multimedia Buttons:** The remote buttons fill the screen with or without the trackpad, use larger symbols and labels, and are tinted by function (media, arrows, OK, Home/End/PgUp/PgDn, presentation, system, mouse). Home, End, PgUp and PgDn follow the full-size keyboard arrangement.
- **Launch Button Shows the Mode:** On Home, the Launch button carries the selected mode's icon and the mode picker beside it shows ^.

### Fixed
- **Multimedia Arrows and Trackpad When Upright:** The arrow pad and trackpad now turn with the phone, so each arrow sends the direction it points and swipes move the pointer the way you swipe.
- **Multimedia With Fullscreen Off:** The screen turns with the phone, so the status bar stays on the top edge when held upright.

## [1.1.0] - 2026-10-09

> [!IMPORTANT]
> **One-time re-pairing required:** Bluke 1.1 introduces a unified Bluetooth HID controller descriptor. When upgrading from 1.0.x, please unpair/forget Bluke on **both** devices and pair fresh using **Scan** in Bluke. This ensures your host operating system caches the updated descriptor correctly.

### Added
- **Multimedia Remote Mode:** A dedicated input surface combining media playback, slide presentation controls, an integrated touchpad, and optional hardware volume-button forwarding.
- **Unified Gamepad Profiles:** Seamless, live switching between **Native** (PC/Linux), **Android** (mobile games), and **Web** (browser gamepad tester) mappings without reconnecting or re-pairing.
- **6 New Switch Sound Profiles:** Expanded built-in mechanical switch acoustics to 12 total profiles, adding Turquoise Tealios, Gateron Black Inks, Cherry MX Blues, Cherry MX Blacks, SKCM Blue Alps, and NovelKeys Creams.
- **Custom Sound Pack Import:** Import and manage custom **Mechvibes ZIP audio sprite packs** directly from storage, with automatic audio decoding, duplicate detection, and deletion controls.
- **International Character Layouts:** Added support for AZERTY, QWERTZ, Dvorak, Colemak, and Russian (ЙЦУКЕН) typing layouts with on-screen `Shift + Space` quick-cycling.
- **Keyboard Customization Studio:** Design custom themes with a visual RGB/HSV color picker, case finishes (metallic/matte), plate accents, keycap groups, and individual key styling overrides.
- **Independent Geometries:** Physical layouts (60%, 65%, 75%) are now completely decoupled from visual themes.
- **Touchpad Side-Rail Modifiers:** Optional mechanical modifier key strip (Ctrl, Alt, Shift, Meta) on the left or right of the touchpad gesture surface.
- **Diagnostic Logging:** Opt-in Bluetooth lifecycle and HID throughput logging under Settings > Support for easier troubleshooting and bug reporting.

### Improved & Fixed
- **Reorganized Settings:** Streamlined hierarchy grouped into *Personalization*, *Controls & connection*, and *Support*, complete with customizable toolbar quick-cycles.
- **Reliable Tap-and-Drag:** Restored standard delayed-click tap-and-drag gesture for smooth, dependable window and file dragging across host platforms.
- **Robust Bluetooth Lifecycle:** Serialized connection coordinator, bounded registration timeouts, and proactive pairing recovery advice prevent stuck connection states.
- **Input Performance:** 125 Hz (8 ms) analog stick report sampling with optimized UI recomposition for low-latency input.
- **Adaptive Remote Layouts:** Multimedia controls dynamically adapt to phone orientation (landscape vs. upright/portrait) with intuitive rotation.

---

## [1.0.7] - 2026-06-21
- Build and configuration maintenance.

## [1.0.5] - 2026-06-20
- Gamepad mode enabled by default.
- Optimized dependencies and reduced application package size.

## [1.0.4] - 2026-06-20
- Added experimental Gamepad controller mode.

## [1.0.0] - 2026-06-05
- Initial public release of Bluke.
- Driverless Bluetooth HID keyboard and touchpad emulation.
- 6 mechanical switch sound profiles (Cherry MX Brown, Holy Panda, Alpaca, Kailh Box Navy, Buckling Spring, Topre).
- Keycap themes (Olivia, Dracula, Oblivion, Retro, Cafe, Mizu).
- Material You dynamic theming, OLED high-contrast dark theme, and system haptics.
