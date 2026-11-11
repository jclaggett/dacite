(ns dacite.peer.peer-cljs-test
  "peer-v1 fixtures and framing on nbb. Run from the repo root:

     npm run peer:test

   The peer-v1 checks (peer_v1_checks.cljc, also run by peer_v1_test.clj
   on the JVM), and the portable verification and
   client and flow checks of M1.2 slices 1 and 2 (client_checks.cljc and
   flow_checks.cljc, also run by client_test.clj and flow_test.clj on the JVM),
   and the M1.3 checks of an asynchronous store (async_checks.cljc, also run by
   async_test.clj): the M1.2 checks again over a store that settles late, and a
   store whose promises are real `js/Promise`s; and the M3.1 transport checks
   (transport_checks.cljc, also run by transport_test.clj): two peers over the
   in-memory `ITransport`."
  (:require [cljs.reader :as reader]
            [dacite.cache-checks :as cache-checks]
            [dacite.host :as host]
            [dacite.peer.async-checks :as async-checks]
            [dacite.peer.client-checks :as client-checks]
            [dacite.peer.conformance :as conf]
            [dacite.peer.flow-checks :as flow-checks]
            [dacite.peer.peer-v1-checks :as peer-v1-checks]
            [dacite.peer.verify :as verify]
            [dacite.reveal :as reveal]
            [dacite.peer.transport-checks :as transport-checks]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.string-vectors :as string-vectors]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]
            [dacite.value.scalar]))

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

(defn- bitmap-u32-unsigned-problems
  "Issue #30: a hamt/bitmap whose bitmap has bit 31 set (chunk 31 in use) must
   decode as an unsigned u32 on nbb. Before the fix, `get-u32` used signed
   `bit-or` and the reconstructed hash did not match."
  []
  (let [src (store/mem-store)
        root (v/hash (apply v/map src (mapcat (fn [i] [(str "k" i) (str "v" i)]) (range 60))))
        pages (reveal/page-order src root)
        mask (host/shl64 (host/word 1) 31)
        victim (first (for [h pages
                            :let [e (store/await (store/s-get src h))]
                            :when (and (= "hamt/bitmap" (first e))
                                       (not (host/word-zero?
                                             (host/band64 (:bitmap (second e)) mask))))]
                        [h e]))]
    (if-not victim
      ["no hamt/bitmap with bit 31 set in a 60-entry map"]
      (let [[h e] victim
            bs (bin/encode-item {:enc :node :hash h :entry e})
            dec (:entry (first (bin/decode-item-stream bs)))
            orig (:bitmap (second e))
            got (:bitmap (second dec))
            same? (= (str orig) (str got))
            verify (try (verify/verify-item! (store/mem-store)
                                             {:enc :node :hash h :entry dec})
                        :ok
                        (catch :default e2 [:fail (ex-message e2)]))]
        (cond-> []
          (not same?)
          (conj (str "bitmap with bit 31 decoded as " (pr-str got)
                     " (type " (pr-str (type got)) "), wanted unsigned " orig))
          (neg? (js/Number got))
          (conj (str "decoded bitmap is negative: " got))
          (not= :ok verify)
          (conj (str "verify-item! of the decoded node: " (pr-str verify))))))))

(def ^:private fs (js/require "fs"))

(def ^:private v1-env
  "Host pieces the peer-v1 checks take (see dacite.peer.peer-v1-checks)."
  {:case-dirs #(let [root (str (conf/fixtures-root) "/cases")]
                 (filter (fn [n] (.isDirectory (.statSync fs (str root "/" n))))
                         (js->clj (.readdirSync fs root))))
   :read-spec #(.readFileSync fs "docs/spec/peer-v1.md" "utf8")
   :read-text #(.readFileSync fs % "utf8")})

(defn -main
  [& _]
  (let [v1 (mapv #(check/run-one peer-v1-checks/checks v1-env %)
                 (sort (keys peer-v1-checks/checks)))
        problems (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) v1)
        client (-> (client-checks/run-all) (into (flow-checks/run-all)) (into (async-checks/run-all)) (into (transport-checks/run-all)))
        client-problems (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) client)
        all (into (vec problems) (concat client-problems (bitmap-u32-unsigned-problems) (string-problems) (string-vectors/behavior-problems) (cache-checks/problems)))]
    (println "peer-v1 checks:" (count v1) "tests, slowest"
             (apply max (map :ms v1)) "ms;" (count all) "problems in all")
    (println "client checks:" (count client) "tests,"
             (count client-problems) "problems, slowest"
             (apply max (map :ms client)) "ms")
    ;; the one check that waits: a store whose promises are real js/Promises
    (async-checks/host-promise-check
     (fn [host]
       (println "host-promise store:" (count host) "problems")
       (doseq [p (concat all (map #(str "host-promise: " %) host))] (println "  FAIL" p))
       (when (or (seq all) (seq host))
         (js/process.exit 1))))))
