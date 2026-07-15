(ns dacite.dev.gen-byte-table
  "JVM-only regeneration tool for the precomputed byte->hash table.

   Dacite's hashing seeds a 256-entry table mapping each byte value to a
   256-bit hash. SHA-256 is used only as the seed function. To keep the
   portable core free of any host crypto dependency (no MessageDigest on
   nbb/ClojureScript, and identical values across future language ports),
   the table is precomputed here and shipped as data in
   `dacite.byte-table` (a plain .cljc vector of 64-char hex strings,
   indexed by unsigned byte value 0..255).

   Run:  clojure -X:dev dacite.dev.gen-byte-table/generate!"
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

(defn- sha256-hex
  "SHA-256 of a single unsigned byte value, as a 64-char lowercase hex
   string. Matches the original seed: sha256(byte-array [signed-byte])."
  [^long u]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md (byte-array [(unchecked-byte u)]))]
    (apply str (map #(format "%02x" (bit-and % 0xFF)) digest))))

(def ^:private target
  "src/dacite/byte_table.cljc")

(defn generate!
  "Write the byte->hash hex table to src/dacite/byte_table.cljc."
  [& _]
  (let [rows (mapv sha256-hex (range 256))
        sb (StringBuilder.)]
    (.append sb ";; GENERATED FILE — do not edit by hand.\n")
    (.append sb ";; Regenerate with: clojure -X:dev dacite.dev.gen-byte-table/generate!\n")
    (.append sb "(ns dacite.byte-table\n")
    (.append sb "  \"Precomputed byte->hash seed table (SHA-256 seeded), shipped as\n")
    (.append sb "   portable data so the core needs no host crypto. Indexed by\n")
    (.append sb "   unsigned byte value 0..255; each entry is a 64-char hex string.\")\n\n")
    (.append sb "(def hex-table\n  [")
    (doseq [[i hex] (map-indexed vector rows)]
      (when (pos? i) (.append sb "\n   "))
      (.append sb (str "\"" hex "\"")))
    (.append sb "])\n")
    (io/make-parents target)
    (spit target (.toString sb))
    (println "Wrote" (count rows) "entries to" target)))
