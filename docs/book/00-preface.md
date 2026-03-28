# Preface

Dacite is a system for **distributed immutable data structures** with
content-addressed nodes. It enables structural sharing, efficient diffs,
lazy fetching, and perfect caching — all built on a single cryptographic
primitive.

## What This Book Covers

The book is organized in four layers, each building on the previous:

1. **Hash Fusion** — the cryptographic primitive. A function that
   combines hashes deterministically, forming a group over 256-bit
   values. Everything else is built from this.

2. **Values** — the data model. Three primitives (scalar, seq, map)
   combine with typed value conventions to produce a rich type system:
   strings, vectors, maps, sets, blobs, and negative sets.

3. **Stores** — persistence and distribution. An abstract protocol for
   storing and retrieving values by hash, with layered implementations
   for caching, persistence, and network access.

4. **Sharing** — authorization and multi-user access. Proof of
   possession, the shares map convention, named groups, and the claim
   protocol. How multiple participants share content-addressed data
   without sacrificing immutability.

Each layer is self-contained. A reader interested only in Dacite as a
data structure library can stop after Chapter 2. Adding persistence
means reading Chapter 3. Multi-user sharing is Chapter 4.

## How to Read This Book

The chapters build sequentially — later chapters reference concepts
from earlier ones. The appendices contain design evolution narratives,
rejected alternatives, and the development story.

For the formal specification, see `SPEC.md`. This book prioritizes
understanding; the spec prioritizes precision. They cover the same
system from different angles.

## Who This Is For

- **Library users** wanting to understand what Dacite does and why
- **Implementors** building Dacite in new languages
- **Distributed systems designers** interested in content-addressed
  authorization models
- **Anyone curious** about what happens when you take hash fusion
  seriously as a foundation

---

*Jonathan Claggett and Gorm, 2026*
