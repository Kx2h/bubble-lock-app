---
version: alpha
name: BubbleLock Calm Console
description: >
  BubbleLock web (landing) and web app (family and operator dashboards).
  An original system that blends two public DESIGN.md inspirations from
  getdesign.md: Apple (white and parchment canvas, near-black ink, 17px body,
  tight display tracking, flat tiles, frosted bars, press scale) and
  PlayStation (true-black chrome, deep dark hero bands, 48px pill CTAs,
  hover-scale tiles with a cyan ring). Applied with the workflow from
  VoltAgent/awesome-claude-design: the file is the reference before any UI
  is written, and it is an inspiration for an original system, not a clone.
colors:
  canvas: "#ffffff"
  canvas-soft: "#f5f5f7"
  surface: "#ffffff"
  surface-soft: "#f5f5f7"
  ink: "#1d1d1f"
  muted: "#636366"
  hairline: "#e0e0e0"
  hairline-soft: "#f0f0f0"
  control-border: "#86868b"
  accent: "#0070d1"
  accent-pressed: "#0064b7"
  accent-text: "#0066cc"
  accent-on-dark: "#53b1ff"
  focus: "#0071e3"
  hover-ring: "#00c3ff"
  chrome: "#000000"
  band-top: "#121314"
  band-bottom: "#000000"
  success: "#18733a"
  warning: "#a05a00"
  danger: "#c81b3a"
typography:
  hero: { size: 56px, weight: 700, line-height: 1.1, tracking: -0.025em }
  display: { size: 34px, weight: 700, line-height: 1.2, tracking: -0.02em }
  title: { size: 28px, weight: 700, line-height: 1.25, tracking: -0.015em }
  headline: { size: 21px, weight: 700, line-height: 1.3, tracking: -0.01em }
  body: { size: 17px, weight: 400, line-height: 1.55, tracking: -0.01em }
  callout: { size: 15px, weight: 400, line-height: 1.5 }
  caption: { size: 13px, weight: 400, line-height: 1.45 }
  button: { size: 16px, weight: 600, line-height: 1 }
rounded: { none: 0, nested: 12px, card: 18px, pill: 9999px }
spacing: [4, 8, 12, 16, 24, 32, 48, 80, 96]
components:
  button-primary: { bg: "{colors.accent}", text: "#ffffff", radius: pill, height: 44px, hero-height: 48px }
  button-secondary: { bg: transparent, border: "1px {colors.control-border}", radius: pill }
  card: { bg: "{colors.surface}", border: "1px {colors.hairline}", radius: card, shadow: none }
  tile-dark: { bg: "linear {colors.band-top} -> {colors.band-bottom}", text: "#ffffff", radius: card }
  sidebar: { bg: "{colors.chrome}", text: "#ffffff" }
  topbar: { bg: "{colors.canvas-soft} at 80% + blur 20px" }
---

# BubbleLock Calm Console

ROLE: WEB-DASHBOARD. DOC-REV: 2.0.0.

## 1. Visual Theme and Atmosphere

Calm, quiet and confident. The page reads like a well-lit room (Apple):
white and parchment surfaces, generous air, one blue for actions. The
frame around it feels like a console (PlayStation): true-black navigation,
a deep dark hero band on each overview, and tiles that lift with a cyan ring
when pointed at. Content stays light so long operational records remain easy
to read; darkness is reserved for chrome and for the single hero band.

Key characteristics:

- True-black chrome. Both sources keep the global navigation at #000000.
  The app sidebar and the landing channel band use it.
- One blue family. #0070d1 fills buttons, #0066cc colors links on light,
  #53b1ff colors links on dark. Cyan #00c3ff appears only as a hover ring.
- Flat by default. Cards sit on a 1px hairline. Depth comes from the change
  of surface color (white card on parchment page, dark band on light page).
- Korean-first type. Headlines use weight 700 with tight tracking; body text
  is 17px with 1.55 line height so Hangul stays open.
- Friendly words. Every visible string uses plain 해요체 Korean. Protocol
  terms appear in parentheses after the plain word, once, for operators.

## 2. Color Palette and Roles

All component colors come from CSS variables in `src/styles.css`. Light
values follow; dark values are set under `prefers-color-scheme: dark`.

| Token | Light | Dark | Role |
| --- | --- | --- | --- |
| --canvas | #f5f5f7 | #000000 | App page background (parchment / black) |
| --surface | #ffffff | #161617 | Cards, panels, inputs |
| --surface-soft | #f5f5f7 | #1f1f21 | Nested rows and tiles inside cards |
| --surface-strong | #e8e8ed | #2c2c2e | Pressed rows, placeholders |
| --ink | #1d1d1f | #f5f5f7 | Primary text |
| --muted | #636366 | #a1a1a6 | Secondary text (5.9:1 / 7:1) |
| --hairline | #e0e0e0 | #2c2c2e | Card borders and dividers |
| --control-border | #86868b | #6e6e73 | Input and secondary button borders (3:1) |
| --accent | #0070d1 | #0070d1 | Primary button fill (white label 4.9:1 in both themes) |
| --accent-pressed | #0064b7 | #0064b7 | Pressed primary |
| --accent-text | #0066cc | #53b1ff | Links and active nav text on light/dark |
| --accent-soft | #e8f1fb | #0a84ff29 | Selected row and active nav background |
| --focus | #0071e3 | #53b1ff | 3px focus outline |
| --hover-ring | #00c3ff | #00c3ff | Hover ring on clickable tiles only |
| --chrome | #000000 | #000000 | Sidebar, landing channel band |
| --band | #121314 -> #000000 | #1c1c1e -> #0b0b0c | Hero band gradient |
| --success | #18733a | #5fd68a | Verified, active |
| --warning | #a05a00 | #ffb340 | Waiting, simulated data |
| --danger | #c81b3a | #ff6b81 | Failure, revoked access |

Status tints use the status color at about 10% (light) or 16% (dark) behind
status text, and every status also carries a word.

## 3. Typography Rules

Font stack (local fonts only, no network download):
`-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Pretendard Variable",
Pretendard, "Noto Sans KR", "Malgun Gothic", "Segoe UI", system-ui, sans-serif`.
Monospace for identifiers: `ui-monospace, "SF Mono", "SFMono-Regular", Consolas, monospace`.

| Role | Size desktop / phone | Weight | Line height | Tracking | Use |
| --- | --- | --- | --- | --- | --- |
| hero | 56 / 34px | 700 | 1.1 | -0.025em | Landing headline |
| display | 34 / 28px | 700 | 1.2 | -0.02em | Page title (h1) |
| title | 28 / 24px | 700 | 1.25 | -0.015em | Hero band headline |
| headline | 21 / 19px | 700 | 1.3 | -0.01em | Card title (h2) |
| body | 17px | 400 | 1.55 | -0.01em | Paragraphs, list text, inputs |
| callout | 15px | 400/600 | 1.5 | 0 | Secondary lines, labels, nav |
| caption | 13px | 400/600 | 1.45 | 0 | Timestamps, legends, fine print |
| metric | 40 / 32px | 700 | 1.1 | -0.02em | Numbers in metric tiles, tabular |

Rules: Korean words stay whole (`word-break: keep-all`); machine identifiers
wrap anywhere. Prose is capped at 75ch. Eyebrows are short Korean words in
13px accent color, with no uppercase English.

## 4. Component Stylings

- **Buttons.** Pill radius. Primary: accent fill, white 16px/600 label,
  44px minimum height (48px in hero bands). Secondary: transparent with a
  1px control border. Quiet: text-only accent label for low-emphasis actions.
  Press state `scale(0.96)`. Disabled: surface-strong fill, muted label.
- **Icon button.** 44px circle, surface-soft fill, always with an accessible
  name.
- **Inputs and selects.** 44px height, 12px radius, 1px control border,
  2px focus border in accent plus the global focus outline.
- **Cards.** Surface fill, 1px hairline, 18px radius, 24px padding (20px on
  phones). Resting cards carry no shadow.
- **Clickable tiles** (entrance cards, quick actions, device cards): hover
  lifts by 2px, scales to 1.01 and shows a 2px cyan ring; press scales to
  0.98. Motion respects `prefers-reduced-motion`.
- **Hero band.** In-app overview banner: band gradient, white text, 18px
  radius, accent pill CTA. One per page.
- **Metric tile.** Surface fill on the parchment page, no border, 18px
  radius, callout label, metric number, caption note. Text values such as a
  connection state use the headline size instead of the metric size.
- **Step number.** 28px accent circle before a card title when two forms
  form one flow (1. 공유 동의 만들기, 2. 사용 권한 켜기).
- **Badges.** Pill, 13px/600, status tint plus status word.
- **Sidebar.** True-black, white 15px nav labels, active item as a
  white-at-12% pill with accent-on-dark icon. Brand at top, return link at
  bottom.
- **Top bar.** Sticky, canvas at 80% with `saturate(180%) blur(20px)`,
  hairline bottom border.
- **Bottom tab bar** (family app, phones): fixed, frosted, five tabs with
  icon and short label, safe-area padding.
- **Segmented control** (map style): pill track in surface-soft, selected
  segment as a surface pill.
- **Stages** (command steps): four cells 수락, 전송, 적용, 확인 in order;
  completed cells show a success dot and KST time with milliseconds.

## 5. Layout Principles

- Shell: 248px black sidebar beside a fluid main column; content width caps
  at 1440px; page padding 40px desktop, 24px tablet, 16px phone.
- Section rhythm inside the app: 24px between cards, 32px before a new page
  section. Landing bands use 96px (desktop) / 64px (phone) vertical padding.
- Grids: metric tiles auto-fit at 200px minimum; two-column forms collapse
  to one column at 1068px; device cards auto-fit at 240px.
- Landing uses three bands (PlayStation channel layout): light hero with the
  keyring render, black channel band with the two entrances, parchment band
  with three plain promises.
- Scroll owner: the document. Raw JSON alone owns horizontal overflow.

## 6. Depth and Elevation

| Level | Treatment | Use |
| --- | --- | --- |
| 0 Flat | Surface color change only | Page, bands, sidebar |
| 1 Hairline | 1px hairline | Cards, rows, inputs |
| 2 Lift | `0 4px 12px rgb(0 0 0 / .16)` + cyan ring | Hovered clickable tiles |
| 3 Product | `3px 5px 30px rgb(0 0 0 / .22)` | The keyring render on the landing only |
| Frost | 80% canvas + blur 20px | Top bar, bottom tab bar, map overlays |

## 7. Do's and Don'ts

Do:

- Keep one blue family for every action and link; use cyan only as a hover ring.
- Keep chrome true black and content light, so the hero band is the only dark block in content.
- Use the pill shape for actions, chips and badges; 18px for containers; 12px for nested items and inputs.
- Show depth through surface color and hairlines; save shadows for hover lift and the keyring render.
- Write plain Korean first and put the protocol term in parentheses once, for example 장치 응답(ACK).
- Say what happened and what the person can do next in every error, for example 실시간 연결이 끊겼어요. 새로고침하면 이어서 받아요.
- Label simulated data as 모의 데이터 (operator) or 체험 데이터 (family) on every surface that shows it.

Instead of:

- A second accent color, use a status color with its word.
- Uppercase English eyebrows, use a short Korean eyebrow.
- Text under 13px, use the caption role.
- Raw enum values (ACTIVE, RETRY_PENDING), use the labels in `src/labels.ts`.

## 8. Responsive Behavior

| Breakpoint | Change |
| --- | --- |
| >= 1069px | Sidebar, two-column forms, four metric tiles in a row |
| <= 1068px | Sidebar narrows to 216px, forms stack, metrics two per row |
| <= 760px | Sidebar becomes a black top bar with brand; operator nav becomes horizontal scrolling pills; family nav moves to the bottom tab bar |
| <= 480px | Hero 34px, display 28px, single-column tiles |

Touch targets stay at 44px minimum on every breakpoint. Phone body text stays
17px. Safe-area insets pad the top bar and the bottom tab bar.

## 9. Agent Prompt Guide

When changing this UI, read this file first, then `src/styles.css` tokens.

- "Add a card": `content-card` + `card-heading` (h2 headline + optional
  quiet link), flat, hairline, 18px radius.
- "Add a status": `<Badge tone="success|warning|danger|info">` with a Korean
  word from `src/labels.ts`.
- "Add copy": 해요체, plain words first, protocol term in parentheses once,
  ASCII punctuation (comma or slash) between items.
- "Add a time": `<Timestamp at>` shows KST with milliseconds for command and
  audit records; `<Timestamp at variant="friendly">` shows 11월 19일 오후 3:20
  for family screens. Both keep the original value in `dateTime` and the title.

## Product rules kept from earlier revisions

- Read-only operations screen. The primary operator action is 새로고침,
  which resumes the stream and refreshes the selected resource source.
- Commands are driven only by validated `/events` messages. Resources and
  audit come from the mock HTTP API; failures show the error and keep data
  sources separate.
- Command stage order: 수락, 전송, 적용, 확인. Command and audit times display
  in KST (UTC+09:00) with milliseconds. Duration shows 기록 없음 when evidence
  is absent. A device response (ACK) means the command arrived; success is
  judged by readback. A `false` readback displays as 꺼짐 (OFF).
- Browser offline keeps prior receipts with offline wording. Reconnect keeps
  reducer state and cursor; 처음부터 다시 받기 clears state and resumes at zero.
- AE/ACP labels are operator context, not authorization. Settings live only
  in React memory; credentials, secrets, localStorage and VITE values stay out.
- Accessibility: skip link, native links and controls, explicit labels,
  visible 3px focus, status by text, polite live regions, alerts for errors,
  light and dark themes from `prefers-color-scheme`.

## Known Gaps

- Visual verification for this revision uses headless Chrome captures at
  390px and 1440px in light and dark, recorded in
  `evidence/2026-11-19-calm-console/`; earlier HIG captures in
  `evidence/hig/` describe the 2026-10-29 screens.
- Map tiles keep their provider styling in dark mode.
- Server-side error messages from `server/portal.ts` and the portal demo keep
  their existing wording.
- Real Mobius reachability, AE/ACP, CORS, RVI, SDT registration, auth and
  service integrations remain to be confirmed on hardware.
