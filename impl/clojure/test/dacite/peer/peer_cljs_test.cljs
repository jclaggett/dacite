(ns dacite.peer.peer-cljs-test
  "peer-v1 fixtures and framing on nbb. Run from the repo root:

     npm run peer:test

   The same checks as peer_v1_test.clj, and the portable verification and
   client and flow checks of M1.2 slices 1 and 2 (client_checks.cljc and
   flow_checks.cljc, also run by client_test.clj and flow_test.clj on the JVM),
   and the M1.3 checks of an asynchronous store (async_checks.cljc, also run by
   async_test.clj): the M1.2 checks again over a store that settles late, and a
   store whose promises are real `js/Promise`s."
  (:require [cljs.reader :as reader]
            [dacite.cache-checks :as cache-checks]
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.kernel-vectors :as kernel-vectors]
            [dacite.peer.async-checks :as async-checks]
            [dacite.peer.client-checks :as client-checks]
            [dacite.peer.conformance :as conf]
            [dacite.peer.examples :as ex]
            [dacite.peer.flow-checks :as flow-checks]
            [dacite.peer.msg :as msg]
            [dacite.store :as store]
            [dacite.string-vectors :as string-vectors]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]
            [dacite.value.scalar]
            [dacite.value.types :as types]))

(defn- fuse-vector-problems
  "The SL(2, F_p) fuse on this host against fixtures/fuse-sl2/vectors.edn, the
   known answers the JVM test (hash_test) also checks: nbb and the JVM must
   agree to the byte."
  []
  (let [fs (js/require "fs")
        vs (reader/read-string (.readFileSync fs "fixtures/fuse-sl2/vectors.edn" "utf8"))
        bad (fn [what expected actual]
              (when-not (= expected actual) [(str "fuse-sl2 " what ": " (pr-str actual) " <> " (pr-str expected))]))]
    (concat
     (when-not (= (str (host/sl2-p)) (:p vs)) ["fuse-sl2 p"])
     (mapcat (fn [{:keys [input state ref]}]
               (let [st (hash/fuse-str input)]
                 (concat (bad (str "state of " (pr-str input)) state (hash/state->hex st))
                         (bad (str "ref of " (pr-str input)) ref (hash/hash->hex (hash/state->ref st))))))
             (:fuse-str vs))
     (mapcat (fn [{:keys [ref state]}]
               (bad (str "ref->state " ref) state (hash/state->hex (hash/ref->state (hash/hex->hash ref)))))
             (:ref->state vs))
     (mapcat (fn [{:keys [type value ref]}]
               (let [v (if (= type "char") (host/char-at value 0) value)]
                 (bad (str "scalar " type " " (pr-str value)) ref
                      (hash/hash->hex (types/typed-value-hash [type v])))))
             (:scalars vs))
     (mapcat (fn [[i hex]] (bad (str "table " i) hex (hash/state->hex (nth hash/byte->state i))))
             (:table-sample vs)))))

(defn- kernel-problems
  "The kernel boundary (`hash/kernel`) against its known answers, the same
   checker the JVM test and a WASM wrapper use."
  []
  (let [fs (js/require "fs")
        vs (reader/read-string (.readFileSync fs "fixtures/fuse-sl2/vectors.edn" "utf8"))]
    (if (seq (:kernel vs))
      (kernel-vectors/problems hash/kernel (:kernel vs))
      ["kernel vectors missing"])))

(defn- string-problems
  "Text on this host against the string vectors (fixtures/fuse-sl2/vectors.edn):
   a string is code points, a lone surrogate is refused, the same as on the JVM
   (unicode_test.clj) and in reference.py."
  []
  (let [fs (js/require "fs")
        vs (reader/read-string (.readFileSync fs "fixtures/fuse-sl2/vectors.edn" "utf8"))]
    (if (and (seq (:strings vs)) (seq (:rejected-strings vs)) (seq (:invalid-utf8 vs)))
      (string-vectors/problems vs)
      ["string vectors missing"])))

(defn- bad-state-problems
  "A measure state outside SL(2, F_p) is refused with ex-info :bad-state on nbb
   as on the JVM (wire/binary_test.clj)."
  []
  (let [st (store/mem-store)
        m (v/vector st (v/string st "aaa") (v/string st "bbb"))
        root (store/await (store/s-get st (v/hash m)))
        h (:root (second root))
        entry (store/await (store/s-get st h))
        bs (js/Uint8Array.from (js/Array.from (bin/encode-item {:enc :node :hash h :entry entry})))
        state (vec (hash/state->bytes (:elements-fuse (:measure (second entry)))))
        off (first (filter #(= state (mapv (fn [i] (aget bs (+ % i))) (range 128)))
                           (range (- (.-length bs) 127))))
        outcome (fn [a] (try (bin/decode-item-stream a) :decoded
                             (catch :default e [(:reason (ex-data e)) (ex-message e)])))
        flipped (fn [at] (let [a (js/Uint8Array.from bs)] (aset a at (bit-xor 1 (aget a at))) a))
        zeroed (let [a (js/Uint8Array.from bs)] (dotimes [i 128] (aset a (+ off i) 0)) a)]
    (concat
     (when-not off ["bad-state: the state is not inside the item"])
     (when-not (= :decoded (outcome bs)) ["bad-state: the untouched item does not decode"])
     (mapcat (fn [[label a]]
               (let [[reason msg] (outcome a)]
                 (when-not (and (= :bad-state reason) (re-find #"bad fuse state" (str msg)))
                   [(str "bad-state: " label " gave " (pr-str [reason msg]))])))
             {"a flipped bit in cell a" (flipped off)
              "a flipped bit in cell d" (flipped (+ off 127))
              "the all-zero state" zeroed}))))

(defn -main
  [& _]
  (let [{:keys [cases problems]} (conf/run-all)
        framing (conf/run-framing)
        encoded (count (for [{:keys [type stream value]} ex/examples
                             :let [bs (msg/encode-message {:type type :stream stream :value value})]]
                         bs))
        reason #(try (%) :accepted (catch :default e (:reason (ex-data e))))
        host (concat
              (when-not (= 256 (count (msg/cut-text (apply str (repeat 300 "x")))))
                ["cut-text does not cut to 256 bytes"])
              (when-not (= :profile (reason #(msg/encode-value {"a" (inc msg/max-int)})))
                ["an integer over 2^53 - 1 is not refused"])
              (when-not (= 2 (get-in (msg/negotiate {"versions" #{1 2} "caps" #{} "limits" {"max-frame" 1 "max-item" 1 "max-streams" 1}}
                                                    {"versions" #{2} "caps" #{} "limits" {"max-frame" 1 "max-item" 1 "max-streams" 1}})
                                     [:ok :version]))
                ["negotiate does not pick the highest common version"]))
        client (-> (client-checks/run-all) (into (flow-checks/run-all)) (into (async-checks/run-all)))
        client-problems (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) client)
        all (into (into (into (vec problems) framing) host) (concat client-problems (fuse-vector-problems) (kernel-problems) (string-problems) (string-vectors/behavior-problems) (bad-state-problems) (cache-checks/problems)))]
    (println "peer-v1 fixtures:" cases "cases," encoded "examples encoded,"
             (count all) "problems")
    (println "client checks:" (count client) "tests,"
             (count client-problems) "problems, slowest"
             (apply max (map :ms client)) "ms")
    ;; the one check that waits: a store whose promises are real js/Promises
    (async-checks/host-promise-check
     (fn [host]
       (println "host-promise store:" (count host) "problems")
       (doseq [p (concat all (map #(str "host-promise: " %) host))] (println "  FAIL" p))
       (when (or (seq all) (seq host) (< cases 50))
         (js/process.exit 1))))))
