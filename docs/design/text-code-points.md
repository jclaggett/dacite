# Text is a sequence of code points (issue #16)

Status: implemented (PR for #16). Hash values of strings with a character outside
the Basic Multilingual Plane (BMP) changed on **both** hosts (the JVM and nbb),
and the two now agree; hashes of BMP-only text are unchanged on both. The page
layout of non-ASCII BMP text changed on nbb (below). Draft, so no version bump
(as for #12 and #14).

## The bug

A string was a run of host characters taken from `(str s)`. On the JVM that is
UTF-16 code units, so U+1F600 was two `char`s, each a lone surrogate, and the
UTF-8 encoder (`String.getBytes`) turned each into `?`: the string hashed as two
`?`. nbb also walked code units (`(seq "😀")` has two elements in ClojureScript)
and encoded each lone half with `TextEncoder` as U+FFFD. So both hosts counted
an emoji as 2 elements, and they differed in what those 2 were:

| text | JVM before | nbb before |
|------|------------|------------|
| `😀` | two `?` (2 elements) | two U+FFFD (2 elements) |
| `\uD83D` alone | one `?` | one U+FFFD |

(The issue guessed nbb already walked by code point. It did not: it agreed on
BMP text and was wrong in a different way.) `count`, `nth`, `slice`, paging and
the hash were all in UTF-16 units.

## The rule

- A string is a sequence of **code points** (Unicode scalar values). One
  element of a string, one `char` scalar, is one code point.
- `count`, `nth`, `slice`, `remove-nth`, the `count` in a `run` of `char`, the
  item counts of pages and the measure's `count` are code points, on every host.
- A page of a string holds whole code points: it never ends inside a surrogate
  pair. A page's text is valid UTF-8 on its own. Size budgets count the UTF-8
  bytes of a code point (1 to 4), so a 4-byte character is never half in a page.
- The hash of a string is the fuse of the hashes of its characters, as before; a
  character's hash is the hash of its UTF-8. Nothing about paging enters it.
- **A lone surrogate is refused** (below). **Malformed UTF-8 is refused.**

Host forms: a host string stays the host's string (UTF-16 on the JVM and in
JavaScript). `dacite.host` has the helpers that map code points to it (`cp-count`,
`cp-offset`, `cp-subs`, `chars-of`, `char-at`, `code-point-at`); every other
namespace goes through them. On the JVM a character is a `char` when it is in the BMP
and a one-code-point `String` when it is not (a `Character` has 16 bits): one
form per character, and `(v/char ctx "a")` is still refused. In ClojureScript a
character is always a one-code-point string. The JVM `CharSequence` view of a
Dacite string uses code point indexes, like `count`; `charAt` of a non-BMP
character throws `:non-bmp-char`.

## The decision: lone surrogates are rejected

Options were to reject, or to replace with one fixed character (U+FFFD) on both
hosts.

**Reject**, with `ex-info` `:reason :lone-surrogate` (`:index` the UTF-16 index,
`:code-unit`), when a string or a char is built, when text is encoded to UTF-8, and
when a size is computed:

- A replacement is silent data loss, and it is not injective: `"\uD83D"`,
  `"\uDE00"` and `"\uFFFD"` would be one value, so two different texts a user wrote
  could not be told apart by hash, and the stored text is not the text they gave.
  Content addressing is the point of Dacite; a hash of text that is not the given
  text is a footgun.
- The wire is UTF-8 and wire-v1 already says invalid UTF-8 is an error. UTF-8 has
  no form for a surrogate, so a lone one cannot be written, and no peer can send
  one. Allowing it in memory would make values that cannot be sent. (WTF-8, which
  can, is not UTF-8 and a decoder in any language would refuse it.)
- It is what Rust (a `String` cannot hold one) and Python (`str.encode` raises)
  do: the data is rejected where it enters the system, not repaired.
- The failure is at the boundary where the caller can still act: they can repair
  with `String.prototype.toWellFormed()` (ES2024) or the host's equivalent, or reject
  their own input. Dacite does not guess which replacement they want.
- I found no strong reason to allow them. The one case for it is round-tripping
  ill-formed JavaScript strings (a string cut at a 16-bit boundary) and those are
  a bug upstream of Dacite.

The check is one scan (`host/lone-surrogate-index`) in `host/utf8-bytes`,
`host/check-text!` (at `string`, `char`), the page chunker, and the literal
size functions, so no path stores or measures such text first. A refused string
leaves nothing in the store.

**The same on the receiving side:** `host/utf8-decode` is strict: malformed
UTF-8 (a bad lead or continuation byte, an overlong form, an encoded surrogate, above
U+10FFFF, a truncation) throws `:reason :invalid-utf8` on the JVM (a
`CharsetDecoder` that reports) and in JS (`TextDecoder` with `fatal`). Before, both
repaired to U+FFFD, which re-encodes to different bytes (the value would not
match its claimed hash, or would hash as another value). `TextDecoder` also dropped
a leading U+FEFF unless `ignoreBOM` is set: nbb now sets it, so a BOM is text
on every host. A `char` that decodes to other than one code point is `:bad-char`.

## What changed on disk and wire

- Hashes of strings and chars of BMP text: no change, on either host. Strings with
  non-BMP characters hash differently on **both** hosts (the JVM hashed `?` per
  half, nbb U+FFFD per half), and now agree.
- **Page layout of non-ASCII BMP text changes on nbb** (hashes do not, since a hash
  does not depend on paging). nbb used to size a character by its UTF-16 unit
  through `(utf8-len (nth s i))`, which saw no character on ClojureScript, so it
  cut 3-byte CJK text at 192 bytes (64 characters) per page against a 74-byte
  budget. It now pages as the JVM does (21 characters), so a store or a wire
  page of such text written by nbb before differs in layout from one written now,
  though both hold the same value. The JVM's layout of BMP text is unchanged.
- The `n` of a `run` of `char` on the wire is code points (it was UTF-16 units; the
  two agree for BMP text). A decoder checks the UTF-8 decodes to `n` code points.
- No wire or store-format version bump: this is draft, there are no values
  in the wild, as for #12 and #14. The first release with users must decide
  whether text hashes need a hash-version bump (the vectors are the evidence).

## Tests

`fixtures/fuse-sl2/vectors.edn` `:strings` `:utf8` `:invalid-utf8`
`:rejected-strings` (and char scalars) are checked by the JVM, nbb and
`reference.py` (`dacite.string-vectors`, `unicode_test.clj`). `unicode_test`
also: paging at ten page budgets with indexing and slicing against the JDK's own
`codePoints`; a chunk cut at every offset of the budget; `split-body-at`; the
wire; and the by-hand hash of the code points.
