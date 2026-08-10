(ns gen-wire-fixtures
  "Generate wire-v1 fixture cases under fixtures/wire-v1/cases from the shipped codec.

   Run from impl/clojure:
     clojure -M:dev -m gen-wire-fixtures"
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.wire.binary :as bin]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]))

(defn write-case!
  [id items-maps & {:keys [budget desc hash-hex]
                    :or {budget 1024}}]
  (let [msg {:budget budget :items items-maps}
        hex (bin/encode-chunk-hex msg)
        dir (io/file (bin/fixture-root) "cases" id)]
    (.mkdirs dir)
    (spit (io/file dir "message.hex") (str hex "\n"))
    (let [hh (or hash-hex
                 (when-let [h (:hash (first items-maps))]
                   (store/hash->hex h)))]
      (when hh (spit (io/file dir "hash.hex") (str hh "\n"))))
    (spit (io/file dir "description.json")
          (or desc (str "{\"title\":\"" id "\"}\n")))
    (println "ok" id "bytes" (/ (count hex) 2))))

(defn- lit-item [h form]
  {:enc :literal :hash h :literal form})

(defn- node-item [h entry]
  {:enc :node :hash h :entry entry})

(defn- scalar-pair!
  "Build scalar value; return [v h entry lit-form]."
  [st type-name body]
  (let [v (scalar/scalar-with-store st type-name body)
        h (types/dacite-hash v)
        entry (store/s-get st h)
        form (pack/literal-of st h)]
    [v h entry form]))

(defn write-scalar-pair!
  "Write chunk-literal-* and chunk-node-* for a scalar."
  [st type-name body id-suffix]
  (let [[_ h entry form] (scalar-pair! st type-name body)
        lit-id (str "chunk-literal-" id-suffix)
        node-id (str "chunk-node-" id-suffix)]
    (write-case! lit-id [(lit-item h form)]
                 :desc (str "{\"title\":\"literal " type-name " " id-suffix "\"}\n"))
    (write-case! node-id [(node-item h entry)]
                 :desc (str "{\"title\":\"node " type-name " " id-suffix "\"}\n"))
    [lit-id node-id]))

(defn -main [& _]
  (let [st (store/mem-store)
        cases (atom [])]

    (letfn [(track! [ids]
              (doseq [id ids] (swap! cases conj id)))]

      ;; --- Public scalars: node + literal ---
      (track! (write-scalar-pair! st "null" nil "null"))
      (track! (write-scalar-pair! st "bool" true "bool-true"))
      (track! (write-scalar-pair! st "bool" false "bool-false"))
      (track! (write-scalar-pair! st "char" \A "char-A"))
      (track! (write-scalar-pair! st "i8" -5 "i8-neg5"))
      (track! (write-scalar-pair! st "i16" -300 "i16-neg300"))
      (track! (write-scalar-pair! st "i32" -100000 "i32-neg100k"))
      (track! (write-scalar-pair! st "i64" 42 "i64-42"))
      (track! (write-scalar-pair! st "i64" 7 "i64-7"))
      (track! (write-scalar-pair! st "u8" 200 "u8-200"))
      (track! (write-scalar-pair! st "u16" 40000 "u16-40k"))
      (track! (write-scalar-pair! st "u32" 3000000000 "u32-3e9"))
      (track! (write-scalar-pair! st "u64" 42 "u64-42"))
      (track! (write-scalar-pair! st "f32" (float 1.5) "f32-1.5"))
      (track! (write-scalar-pair! st "f32" (float -1.5) "f32-neg1.5"))
      (track! (write-scalar-pair! st "f64" 3.5 "f64-3.5"))
      (track! (write-scalar-pair! st "negative" nil "negative"))

      (let [u256-data (byte-array (map unchecked-byte (range 32)))
            [_ h entry form] (scalar-pair! st "u256" u256-data)]
        (write-case! "chunk-literal-u256"
                     [(lit-item h form)]
                     :desc "{\"title\":\"literal u256 0..31\"}\n")
        (write-case! "chunk-node-u256"
                     [(node-item h entry)]
                     :desc "{\"title\":\"node u256 0..31\"}\n")
        (swap! cases conj "chunk-literal-u256" "chunk-node-u256"))

      ;; --- Collections ---
      (let [blob-v (coll/blob-with-store st (byte-array [0 1 2 255 10]))
            bh (types/dacite-hash blob-v)
            empty-blob (coll/blob-with-store st (byte-array 0))
            ebh (types/dacite-hash empty-blob)
            estr (coll/string-with-store st "hello")
            estrh (types/dacite-hash estr)
            empty-str (coll/string-with-store st "")
            empty-str-h (types/dacite-hash empty-str)
            v123 (coll/vector-with-store st 1 2 3)
            v123h (types/dacite-hash v123)
            ve (coll/vector-with-store st)
            veh (types/dacite-hash ve)
            mab (coll/hash-map-with-store st "a" 1 "b" 2)
            mabh (types/dacite-hash mab)
            em (coll/hash-map-with-store st)
            emh (types/dacite-hash em)
            s123 (coll/dacite-set-with-store st 1 2 3)
            s123h (types/dacite-hash s123)
            es (coll/dacite-set-with-store st)
            esh (types/dacite-hash es)]

        (doseq [[id h]
                [["chunk-literal-blob-bytes" bh]
                 ["chunk-literal-blob-empty" ebh]
                 ["chunk-literal-string-hello" estrh]
                 ["chunk-literal-empty-string" empty-str-h]
                 ["chunk-literal-vector-123" v123h]
                 ["chunk-literal-empty-vector" veh]
                 ["chunk-literal-map-ab" mabh]
                 ["chunk-literal-empty-map" emh]
                 ["chunk-literal-set-123" s123h]
                 ["chunk-literal-empty-set" esh]]]
          (write-case! id [(lit-item h (pack/literal-of st h))]
                       :desc (str "{\"title\":\"" id "\"}\n"))
          (swap! cases conj id))

        (doseq [[id h]
                [["chunk-node-blob-header" bh]
                 ["chunk-node-string-hello" estrh]
                 ["chunk-node-empty-string-header" empty-str-h]
                 ["chunk-node-vector-123" v123h]
                 ["chunk-node-empty-vector-header" veh]
                 ["chunk-node-map-ab" mabh]
                 ["chunk-node-empty-map-header" emh]
                 ["chunk-node-set-123" s123h]
                 ["chunk-node-empty-set-header" esh]]]
          (write-case! id [(node-item h (store/s-get st h))]
                       :desc (str "{\"title\":\"" id "\"}\n"))
          (swap! cases conj id))

        ;; mixed vector
        (let [vm (coll/vector-with-store st "x" "y" 9)
              vmh (types/dacite-hash vm)]
          (write-case! "chunk-literal-vector-mixed"
                       [(lit-item vmh (pack/literal-of st vmh))]
                       :desc "{\"title\":\"literal vector string+string+i64\"}\n")
          (swap! cases conj "chunk-literal-vector-mixed")))

      ;; --- FT / HAMT spine nodes + intermediate literals ---
      (let [leaf-hs (mapv #(types/dacite-hash (scalar/i64-with-store st %)) [1 2 3])
            dig (ft/ft-digit-from-value-hashes st leaf-hs)
            dig-h dig
            dig-entry (store/s-get st dig-h)
            deep-hs (mapv #(types/dacite-hash (scalar/i64-with-store st %)) (range 1 40))
            deep-h (ft/ft-from-value-hashes st deep-hs)
            deep-entry (store/s-get st deep-h)
            ;; small node: force multi-child node if available
            node-hs (mapv #(types/dacite-hash (scalar/i64-with-store st %)) (range 10))
            node-root (ft/ft-from-value-hashes st node-hs)
            ;; walk store for an ft/node entry
            ft-node-entry (some (fn [[h e]]
                                  (when (= "ft/node" (types/entry-type e))
                                    [h e]))
                                (store/s-snapshot st))
            ft-empty-h (ft/ft-empty st)
            ft-empty-entry (store/s-get st ft-empty-h)]

        (when dig-entry
          (write-case! "chunk-node-ft-digit"
                       [(node-item dig-h dig-entry)]
                       :desc "{\"title\":\"ft/digit as store node\"}\n")
          (swap! cases conj "chunk-node-ft-digit")
          (when-let [form (pack/literal-of st dig-h)]
            (write-case! "chunk-literal-ft-digit"
                         [(lit-item dig-h form)]
                         :desc "{\"title\":\"ft/digit intermediate leaf-payload literal\"}\n")
            (swap! cases conj "chunk-literal-ft-digit")))

        (when deep-entry
          (write-case! "chunk-node-ft-deep"
                       [(node-item deep-h deep-entry)]
                       :desc "{\"title\":\"ft/deep as store node\"}\n")
          (swap! cases conj "chunk-node-ft-deep")
          (when-let [form (pack/literal-of st deep-h)]
            (write-case! "chunk-literal-ft-deep"
                         [(lit-item deep-h form)]
                         :desc "{\"title\":\"ft/deep intermediate leaf-payload literal\"}\n")
            (swap! cases conj "chunk-literal-ft-deep")))

        (when ft-node-entry
          (let [[nh ne] ft-node-entry]
            (write-case! "chunk-node-ft-node"
                         [(node-item nh ne)]
                         :desc "{\"title\":\"ft/node as store node\"}\n")
            (swap! cases conj "chunk-node-ft-node")))

        (write-case! "chunk-node-ft-empty"
                     [(node-item ft-empty-h ft-empty-entry)]
                     :desc "{\"title\":\"ft/empty as store node\"}\n")
        (swap! cases conj "chunk-node-ft-empty")
        (when-let [form (pack/literal-of st ft-empty-h)]
          (write-case! "chunk-literal-ft-empty"
                       [(lit-item ft-empty-h form)]
                       :desc "{\"title\":\"ft/empty intermediate literal\"}\n")
          (swap! cases conj "chunk-literal-ft-empty")))

      ;; HAMT
      (let [kh (types/dacite-hash (scalar/i64-with-store st 1))
            vh (types/dacite-hash (scalar/i64-with-store st 2))
            empty-h (hamt/hamt-empty st)
            empty-e (store/s-get st empty-h)
            entry-h (hamt/hamt-entry-node st kh kh vh)
            entry-e (store/s-get st entry-h)
            ;; bitmap: several entries
            pairs (mapv (fn [i]
                          (let [k (types/dacite-hash (scalar/i64-with-store st i))
                                v (types/dacite-hash (scalar/i64-with-store st (+ i 100)))]
                            [k k v]))
                        (range 8))
            bitmap-h (hamt/hamt-from-entries st pairs)
            bitmap-e (store/s-get st bitmap-h)]

        (write-case! "chunk-node-hamt-empty"
                     [(node-item empty-h empty-e)]
                     :desc "{\"title\":\"hamt/empty as store node\"}\n")
        (swap! cases conj "chunk-node-hamt-empty")
        (when-let [form (pack/literal-of st empty-h)]
          (write-case! "chunk-literal-hamt-empty"
                       [(lit-item empty-h form)]
                       :desc "{\"title\":\"hamt/empty intermediate literal\"}\n")
          (swap! cases conj "chunk-literal-hamt-empty"))

        (write-case! "chunk-node-hamt-entry"
                     [(node-item entry-h entry-e)]
                     :desc "{\"title\":\"hamt/entry as store node\"}\n")
        (swap! cases conj "chunk-node-hamt-entry")
        (when-let [form (pack/literal-of st entry-h)]
          (write-case! "chunk-literal-hamt-entry"
                       [(lit-item entry-h form)]
                       :desc "{\"title\":\"hamt/entry intermediate literal\"}\n")
          (swap! cases conj "chunk-literal-hamt-entry"))

        (write-case! "chunk-node-hamt-bitmap"
                     [(node-item bitmap-h bitmap-e)]
                     :desc "{\"title\":\"hamt/bitmap as store node\"}\n")
        (swap! cases conj "chunk-node-hamt-bitmap")
        (when-let [form (pack/literal-of st bitmap-h)]
          (write-case! "chunk-literal-hamt-bitmap"
                       [(lit-item bitmap-h form)]
                       :desc "{\"title\":\"hamt/bitmap intermediate literal\"}\n")
          (swap! cases conj "chunk-literal-hamt-bitmap")))

      ;; multi-item / mixed (keep legacy scenarios)
      (let [a (scalar/i64-with-store st 1)
            b (scalar/i64-with-store st 2)
            ah (types/dacite-hash a)
            bh (types/dacite-hash b)]
        (write-case! "chunk-two-node-i64s"
                     [(node-item ah (store/s-get st ah))
                      (node-item bh (store/s-get st bh))]
                     :budget 0
                     :desc "{\"title\":\"two node i64s\"}\n"
                     :hash-hex (store/hash->hex ah))
        (swap! cases conj "chunk-two-node-i64s")
        (let [s (coll/string-with-store st "hi")
              sh (types/dacite-hash s)]
          (write-case! "chunk-mixed-node-and-literal"
                       [(node-item ah (store/s-get st ah))
                        (lit-item sh (pack/literal-of st sh))]
                       :desc "{\"title\":\"node i64 + literal string\"}\n"
                       :hash-hex (store/hash->hex ah))
          (swap! cases conj "chunk-mixed-node-and-literal")))

      ;; Preserve multi-chunk 3000-string if already present (do not regenerate here)
      (doseq [id ["chunk-string-3000-part-0"
                  "chunk-string-3000-part-1"
                  "chunk-string-3000-part-2"
                  "chunk-literal-todo-seed"]]
        (when (.isDirectory (io/file (bin/fixture-root) "cases" id))
          (swap! cases conj id)))

      ;; Write manifest covering all case directories that exist
      (let [cases-dir (io/file (bin/fixture-root) "cases")
            all (->> (.listFiles cases-dir)
                     (filter #(.isDirectory %))
                     (map #(.getName %))
                     sort
                     vec)
            manifest {:format "dacite-wire-v1"
                      :spec "docs/spec/wire-v1.md"
                      :cases (mapv (fn [id] {:id id :notes id}) all)}]
        (spit (io/file (bin/fixture-root) "manifest.json")
              (str (json/generate-string manifest {:pretty true}) "\n"))
        (println "manifest cases" (count all))
        (println "done")))))
