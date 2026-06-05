# User Types (Future Work)

**Status:** Design vision only. Not scheduled for implementation.

## Overview

User Types (UTs) are Dacite's future open type system, analogous to database
schemas and stored procedures. Types themselves are Dacite values — enabling
content-addressed type definitions that can be shared, versioned, and extended
without core changes.

## Components

A User Type consists of two parts, both stored as Dacite values:

### 1. Shape

Defines how data of this type is actually stored. Uses a grammar that permits
recursive shapes.

Example: a playing card type might have the shape of a map with `suit` (string)
and `value` (number) keys.

### 2. Operations

Functional logic over the shape. Two categories:

- **Getters** — take a Dacite Value conforming to the shape, return a derived value
- **Updaters** — take a shaped value + parameters, return a new value conforming to the shape

Operation specs define behavior in a small functional language. Jonathan has been
exploring [anascript](https://github.com/jclaggett/anascript) as a candidate.

## Requirements

- Both shape and operation specs must be stored as Dacite values
- Types themselves have user types (bootstrap via well-known primitive types)
- New types can be added without modifying core code
- Types are identified by hash, not string name

## Implications for Current Type Implementation

The type system we build now should be "open" — built on patterns that can
extend to user-defined types:

- Type tags should be hash-based, not string-based
- Encoding/decoding should be dispatch-based (multimethods or similar)
- Leave hooks for user-defined operations (getters, updaters)
- Core primitive types are simply well-known entries in the type registry

## Open Questions

- Type registry: how are types registered and discovered?
- Bootstrap: how do the first types get their types?
- Performance: interpreting operation specs vs compiled implementations
- Validation: how do shapes enforce structure?

---
*Captured 2026-06-05 from discussion with Jonathan*
