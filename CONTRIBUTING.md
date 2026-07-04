# Contributing to OpenFugu

Thanks for your interest! Bug reports, ideas, and pull requests are all welcome.

## Getting started

- **Build instructions** are in the [README](README.md#building).
- **App structure and design decisions** are documented in [ARCHITECTURE.md](ARCHITECTURE.md).
- **The roadmap** lives in [IDEAS.md](IDEAS.md) — a good place to find something to work on
  or to check whether an idea is already planned.
- **The BLE protocol** (for anything device-related) is documented in [PROTOCOL.md](PROTOCOL.md).

## Ground rules

- Keep the safety principle: games and exercises must **never incentivize maximum
  pressure** — reward precision and control, not force.
- Match the existing code style and reuse shared components before creating new ones
  (see `ui/SharedComponents.kt`, `game/GameUtils.kt`, `PressureChart.kt`).
- Spell out words in user-facing text ("equalization", not "EQ").
- Compile before submitting: `./gradlew compileDebugKotlin`.

## Licensing of contributions

OpenFugu is licensed under the **GNU General Public License v3.0** with an
**additional permission** allowing distribution through app stores (see
[LICENSE](LICENSE) and [LICENSE-EXCEPTION](LICENSE-EXCEPTION)).

By submitting a contribution to this repository, you agree that your contribution
is licensed under the same terms: **GPL-3.0 together with the additional
permission in LICENSE-EXCEPTION**. This is the standard "inbound = outbound"
model — your contribution is licensed to the project exactly as the project is
licensed to you. You retain your copyright.
