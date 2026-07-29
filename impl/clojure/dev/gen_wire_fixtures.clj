(ns gen-wire-fixtures
  "One-shot: generate wire-v1 fixture cases under fixtures/wire-v1/cases."
  (:require [clojure.java.io :as io]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.wire.binary :as bin]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]))

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

(defn -main [& _]
  (let [st (store/mem-store)
        ;; 1. blob
        blob-v (coll/blob-with-store st (byte-array [0 1 2 255 10]))
        bh (types/dacite-hash blob-v)
        bform (pack/literal-of st bh)
        empty-blob (coll/blob-with-store st (byte-array 0))
        ebh (types/dacite-hash empty-blob)
        ebform (pack/literal-of st ebh)
        blob-entry (store/s-get st bh)

        ;; 2. char, f64, bool false
        ch (scalar/dacite-char-with-store st \A)
        chh (types/dacite-hash ch)
        f (scalar/f64-with-store st 3.5)
        fh (types/dacite-hash f)
        bf (scalar/bool-with-store st false)
        bfh (types/dacite-hash bf)

        ;; 3. empty map, set, string (+ headers as node)
        em (coll/hash-map-with-store st)
        emh (types/dacite-hash em)
        emform (pack/literal-of st emh)
        es (coll/dacite-set-with-store st)
        esh (types/dacite-hash es)
        esform (pack/literal-of st esh)
        estr (coll/string-with-store st "")
        estrh (types/dacite-hash estr)
        estrform (pack/literal-of st estrh)
        vec-empty (coll/vector-with-store st)
        veh (types/dacite-hash vec-empty)]

    (write-case! "chunk-literal-blob-bytes"
                 [{:enc :literal :hash bh :literal bform}]
                 :desc "{\"title\":\"literal blob [0,1,2,255,10]\"}\n")

    (write-case! "chunk-literal-blob-empty"
                 [{:enc :literal :hash ebh :literal ebform}]
                 :desc "{\"title\":\"literal empty blob\"}\n")

    (write-case! "chunk-node-blob-header"
                 [{:enc :node :hash bh :entry blob-entry}]
                 :desc "{\"title\":\"blob collection header as node\"}\n"
                 :hash-hex (store/hash->hex bh))

    (write-case! "chunk-literal-char-A"
                 [{:enc :literal :hash chh
                   :literal {:type "char" :body \A}}]
                 :desc "{\"title\":\"literal char A\"}\n")

    (write-case! "chunk-literal-f64-3.5"
                 [{:enc :literal :hash fh
                   :literal {:type "f64" :body 3.5}}]
                 :desc "{\"title\":\"literal f64 3.5\"}\n")

    (write-case! "chunk-literal-bool-false"
                 [{:enc :literal :hash bfh
                   :literal {:type "bool" :body false}}]
                 :desc "{\"title\":\"literal bool false\"}\n")

    (write-case! "chunk-node-char-A"
                 [{:enc :node :hash chh :entry (store/s-get st chh)}]
                 :desc "{\"title\":\"node scalar char A\"}\n")

    (write-case! "chunk-node-f64-3.5"
                 [{:enc :node :hash fh :entry (store/s-get st fh)}]
                 :desc "{\"title\":\"node scalar f64 3.5\"}\n")

    (write-case! "chunk-literal-empty-map"
                 [{:enc :literal :hash emh :literal emform}]
                 :desc "{\"title\":\"literal empty map\"}\n")

    (write-case! "chunk-literal-empty-set"
                 [{:enc :literal :hash esh :literal esform}]
                 :desc "{\"title\":\"literal empty set\"}\n")

    (write-case! "chunk-literal-empty-string"
                 [{:enc :literal :hash estrh :literal estrform}]
                 :desc "{\"title\":\"literal empty string\"}\n")

    (write-case! "chunk-node-empty-vector-header"
                 [{:enc :node :hash veh :entry (store/s-get st veh)}]
                 :desc "{\"title\":\"empty vector collection header as node\"}\n")

    (write-case! "chunk-node-empty-map-header"
                 [{:enc :node :hash emh :entry (store/s-get st emh)}]
                 :desc "{\"title\":\"empty map collection header as node\"}\n")

    (write-case! "chunk-node-empty-set-header"
                 [{:enc :node :hash esh :entry (store/s-get st esh)}]
                 :desc "{\"title\":\"empty set collection header as node\"}\n")

    (write-case! "chunk-node-empty-string-header"
                 [{:enc :node :hash estrh :entry (store/s-get st estrh)}]
                 :desc "{\"title\":\"empty string collection header as node\"}\n")

    (println "done")))
