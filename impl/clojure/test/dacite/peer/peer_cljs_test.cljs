(ns dacite.peer.peer-cljs-test
  "peer-v1 fixtures and framing on nbb. Run from the repo root:

     npm run peer:test

   The same checks as peer_v1_test.clj, minus hash verification of the
   entries (that lives in the JVM-only service namespace until M2)."
  (:require [cljs.reader :as reader]
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.peer.conformance :as conf]
            [dacite.peer.examples :as ex]
            [dacite.peer.msg :as msg]
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
               (let [v (if (= type "char") (first value) value)]
                 (bad (str "scalar " type " " (pr-str value)) ref
                      (hash/hash->hex (types/typed-value-hash [type v])))))
             (:scalars vs))
     (mapcat (fn [[i hex]] (bad (str "table " i) hex (hash/state->hex (nth hash/byte->state i))))
             (:table-sample vs)))))

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
        all (into (into (into (vec problems) framing) host) (fuse-vector-problems))]
    (println "peer-v1 fixtures:" cases "cases," encoded "examples encoded,"
             (count all) "problems")
    (doseq [p all] (println "  FAIL" p))
    (when (or (seq all) (< cases 50))
      (js/process.exit 1))))

