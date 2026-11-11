(ns dacite.peer.peer-v1-test
  "Peer protocol v1: golden frames, framing rules, message shapes.

   The fixtures are fixtures/peer-v1/cases, written by
   dev/gen_peer_fixtures.clj. Spec: docs/spec/peer-v1.md.

   The tests are checks in `dacite.peer.peer-v1-checks`, shared with nbb
   (`npm run peer:test`); each runs here as a deftest under its old name.
   The two properties are JVM only (test.check)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.conformance :as conf]
            [dacite.peer.msg :as msg]
            [dacite.peer.peer-v1-checks :as checks]
            [dacite.store.check :as check]
            [dacite.wire.binary :as bin]))

(def ^:private env
  "Host pieces the checks take (see dacite.peer.peer-v1-checks)."
  {:case-dirs #(->> (.listFiles (java.io.File. (str (conf/fixtures-root) "/cases")))
                    (filter (fn [^java.io.File f] (.isDirectory f)))
                    (map (fn [^java.io.File f] (.getName f))))
   :read-spec #(slurp (str (.getParentFile (java.io.File. (conf/fixtures-root))) "/../docs/spec/peer-v1.md"))
   :read-text slurp})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest every-fixture-decodes-and-re-encodes
  (run-check "every-fixture-decodes-and-re-encodes"))

(deftest manifest-lists-every-case-directory
  (run-check "manifest-lists-every-case-directory"))

(deftest every-example-has-a-fixture
  (run-check "every-example-has-a-fixture"))

(deftest framing-rules
  (run-check "framing-rules"))

(deftest frame-sizes
  (run-check "frame-sizes"))

(deftest stream-ids-span-u32
  (run-check "stream-ids-span-u32"))

(deftest stream-id-is-not-the-hash
  (run-check "stream-id-is-not-the-hash"))

;; ---------------------------------------------------------------------------
;; Property: any profile value round-trips and re-encodes to the same bytes.
;; ---------------------------------------------------------------------------

(def gen-hex
  (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789abcdef") 64)))

(def gen-leaf
  (gen/one-of [(gen/return nil)
               gen/boolean
               (gen/fmap long gen/small-integer)
               gen/string-alphanumeric
               (gen/fmap msg/hash-ref gen-hex)]))

;; Blobs appear only as map values (no v1 message holds one now). A vector with a
;; blob and another element does not realize in the core today:
;; (msg/encode-value [(byte-array 1) nil]) throws "cannot intern page-body
;; lit item". Reported in the M1.1 PR; not worked around here.
(def gen-blob
  (gen/fmap #(byte-array (map unchecked-byte %)) (gen/vector gen/byte 0 20)))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/fmap set (gen/vector (gen/one-of [gen/string-alphanumeric
                                                         (gen/fmap long gen/small-integer)])
                                            0 4))
                  (gen/map gen/string-alphanumeric inner {:max-elements 4})]))
   gen-leaf))

(defspec blob-fields-round-trip 50
  (prop/for-all [m (gen/map gen/string-alphanumeric gen-blob {:max-elements 3})
                 n gen/small-integer]
    (let [x (assoc m "n" (long n))
          bs (msg/encode-value x)]
      (and (msg/equal-values? x (msg/decode-value bs))
           (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value (msg/decode-value bs))))))))

(defspec any-profile-value-round-trips 150
  (prop/for-all [x gen-value]
    (let [bs (msg/encode-value x)
          back (msg/decode-value bs)]
      (and (msg/equal-values? x back)
           (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value back)))))))

(deftest outside-the-profile
  (run-check "outside-the-profile"))

;; ---------------------------------------------------------------------------
;; Review of PR #7: F1-F4 and the cheap should-fix items
;; ---------------------------------------------------------------------------



(deftest f1-unknown-error-code-is-read-as-internal
  (run-check "f1-unknown-error-code-is-read-as-internal"))

(deftest f2-item-encodings-in-item-streams
  (run-check "f2-item-encodings-in-item-streams"))

(deftest f3-message-types-and-streams
  (run-check "f3-message-types-and-streams"))

(deftest f3-stream-table-parity-and-duplicates
  (run-check "f3-stream-table-parity-and-duplicates"))

(deftest f4-unknown-policy-is-answered-not-refused
  (run-check "f4-unknown-policy-is-answered-not-refused"))

(deftest s1-max-hashes-follows-max-frame
  (run-check "s1-max-hashes-follows-max-frame"))

(deftest s2-strings-and-sets-are-bounded
  (run-check "s2-strings-and-sets-are-bounded"))

(deftest s5-integers-are-within-2-to-the-53
  (run-check "s5-integers-are-within-2-to-the-53"))

(deftest s9-version-negotiation
  (run-check "s9-version-negotiation"))

(deftest s8-reassembly-copies-an-item-once-and-handles-many-small-ones
  (run-check "s8-reassembly-copies-an-item-once-and-handles-many-small-ones"))

(deftest s3-interest-bounds
  (run-check "s3-interest-bounds"))

(deftest nit-hello-zero-limits
  (run-check "nit-hello-zero-limits"))

(deftest frame-parser-does-not-trust-a-length-past-max-item
  (run-check "frame-parser-does-not-trust-a-length-past-max-item"))

(deftest frame-parser-is-linear
  (run-check "frame-parser-is-linear"))

;; S4: the spec's own hex, written by hand from the layout rules, is the
;; same bytes as the fixture frames.
(deftest spec-hex-blocks-match-fixtures
  (run-check "spec-hex-blocks-match-fixtures"))

(deftest every-check-has-a-deftest
  (is (every? #(-> (ns-interns 'dacite.peer.peer-v1-test) (get (symbol %)) meta :test)
              (keys checks/checks))
      "each check in peer_v1_checks.cljc has a deftest here"))
