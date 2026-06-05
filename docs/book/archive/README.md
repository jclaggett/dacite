# Archived Chapters

These chapters document the **authorization and sharing model** that was designed
for a **shared multi-user store**. They are archived because the architecture has
shifted to **dedicated stores per user**, which removes the need for Proof of
Possession and sharing mechanisms entirely.

## Contents

- `04-authorization/` — Proof of Possession, GET/PUT, auth stores, garbage collection
- `05-sharing/` — Shares map, claim mechanism, conventions

## Why Archived

**New direction (2026-06-05):** Each user has their own dedicated store.

Key implications:

- **No shared store** → No need for Proof of Possession (PoP)
- **No PoP** → No authorization layer to gate access
- **No authorization** → No sharing mechanisms (shares map, claim, etc.)
- **Dedicated stores** → User isolation by architecture, not by convention

This is a fundamental simplification: instead of building access control on top
of a shared content-addressed store, we give each user their own store and let
higher-level protocols handle cross-user interactions.

## Salvageable Ideas

Some concepts may be useful in future designs:

- Content-addressed root management
- Store backend abstractions
- Delegation concepts (may map to cross-store protocols)

---
*Archived 2026-06-05*
