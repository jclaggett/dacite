# Preface

Dacite lets you write programs over **immutable values** while **stores**
persist and transmit them. Values are content-addressed: identity is the
hash, updates return new values, unchanged parts are shared. A rooted store
adds one mutable root hash so a running program has a current snapshot.

This book is for people **writing applications**. Implementors of a new host
or store backend will want [How it works](./01-stores/chapter.md) as well.

## Reading

1. [The Dacite way](./the-dacite-way.md) — the stance on data.
2. [Install](./getting-started/install.md) — nbb (fastest) or JVM.
3. [Anatomy of a Dacite app](./building/anatomy.md) — Values / Store split,
   `v/root`, public API.
4. One tutorial that matches what you are building (a document, history, a
   large sequence, two writers, blobs, a browser UI).
5. The [cookbook](./guide/read.md) for field reads, updates, and commit loops.
6. [Values](./reference/values.md) and [Stores](./reference/stores.md) when
   you need a function.

[How it works](./01-stores/chapter.md) is the four-layer model (content
stores, hash fusion, values, rooted stores) plus pack transport. Skip it
until the application recipe is clear — or until you are changing the
library.

## For

Application authors first. Implementors and designers second.

*Jonathan & Gorm, 2026*
