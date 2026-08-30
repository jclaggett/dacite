(ns dacite.service-test
  "Protocol tests for dacite.service handlers and live HttpServer."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.pack :as pack]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]
            [dacite.examples.todo :as todo]))

(deftest handle-request-node-and-root
  (let [rooted (svc/make-demo-rooted)
        h (store/hex->hash (apply str (repeat 64 "a")))
        node ["i64" 42]
        put (svc/handle-request rooted "PUT" (str "/node/" (store/hash->hex h))
                                (wire/write-edn node))]
    (is (= 200 (:status put)))
    (is (= :partial (:status (wire/read-edn (:body put)))))
    (let [put2 (svc/handle-request rooted "PUT" (str "/node/" (store/hash->hex h))
                                   (wire/write-edn node))]
      (is (= 200 (:status put2)))
      (is (= :complete (:status (wire/read-edn (:body put2))))
          "second PUT of same hash is complete (already exists)"))
    (let [got (svc/handle-request rooted "GET" (str "/node/" (store/hash->hex h)) nil)
          raw (svc/handle-request rooted "GET" (str "/node/" (store/hash->hex h) "?raw=1") nil)]
      (is (= 200 (:status got)))
      (is (true? (:dacite.wire/chunk-v1 (wire/read-edn (:body got))))
          "GET /node pack mode returns a chunk envelope")
      (is (= 200 (:status raw)))
      (is (= node (wire/read-edn (:body raw)))))
    (let [miss (svc/handle-request rooted "GET"
                                   (str "/node/" (apply str (repeat 64 "b"))) nil)]
      (is (= 404 (:status miss))))
    (let [r0 (svc/handle-request rooted "GET" "/root" nil)]
      (is (= 200 (:status r0)))
      (is (nil? (:root (wire/read-edn (:body r0))))))
    (let [hex (store/hash->hex h)
          cas-ok (svc/handle-request rooted "POST" "/root/cas"
                                     (wire/write-edn {:expected nil :new hex}))
          cas-bad (svc/handle-request rooted "POST" "/root/cas"
                                      (wire/write-edn {:expected nil :new hex}))]
      (is (= 200 (:status cas-ok)))
      (is (true? (:ok (wire/read-edn (:body cas-ok)))))
      (is (= 409 (:status cas-bad)))
      (is (false? (:ok (wire/read-edn (:body cas-bad)))))
      (let [r1 (svc/handle-request rooted "GET" "/root" nil)]
        (is (= hex (:root (wire/read-edn (:body r1)))))))))

(deftest handle-request-nodes-pack-is-node-only
  (let [rooted (svc/make-demo-rooted)
        vec (coll/vector-with-store rooted 1 2 3)
        vh (types/dacite-hash vec)
        resp (svc/handle-request rooted "GET"
                                 (str "/node/" (store/hash->hex vh) "?nodes=1")
                                 nil)
        chunk (wire/read-edn (:body resp))]
    (is (= 200 (:status resp)))
    (is (true? (:dacite.wire/chunk-v1 chunk)))
    (is (seq (:items chunk)))
    (is (every? #(= :node (:encoding %)) (:items chunk))
        "GET ?nodes=1 is a pack of store nodes, no rematerialized literals")))

(deftest live-server-remote-client-roundtrip
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [remote (remote/remote-store base-url)
            node ["bool" true]
            hex (apply str (repeat 64 "c"))
            h (store/hex->hash hex)]
        (store/s-put remote h node)
        (is (= node (store/s-get remote h)))
        (is (true? (store/s-has? remote h)))
        (is (nil? (remote/remote-get-root remote)))
        (is (true? (remote/remote-cas-root! remote nil h)))
        (is (= h (remote/remote-get-root remote)))
        (is (false? (remote/remote-cas-root! remote nil h))))
      (finally
        (stop!)))))

(deftest binary-chunk-post-and-get-accept
  (let [rooted (svc/make-demo-rooted)
        st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        form (pack/literal-of st h)
        wire-chunk {:budget 1024
                    :items [{:enc :literal :hash h :literal form}]}
        body (bin/encode-chunk wire-chunk)
        post (svc/handle-request rooted "POST" "/nodes" body
                                 {:content-type bin/content-type-chunk-v1})]
    (is (= 200 (:status post)))
    (is (true? (:ok (wire/read-edn (:body post)))))
    (is (store/s-has? rooted h))
    (let [get-bin (svc/handle-request rooted "GET"
                                      (str "/node/" (store/hash->hex h))
                                      nil
                                      {:accept bin/content-type-chunk-v1})
          get-edn (svc/handle-request rooted "GET"
                                      (str "/node/" (store/hash->hex h))
                                      nil
                                      {:accept "application/edn"})]
      (is (= 200 (:status get-bin)))
      (is (= bin/content-type-chunk-v1 (:content-type get-bin)))
      (is (bytes? (:body get-bin)))
      (let [decoded (bin/decode-pack-edn (:body get-bin))]
        (is (true? (:dacite.wire/chunk-v1 decoded)))
        (is (seq (:items decoded))))
      (is (= 200 (:status get-edn)))
      (is (string? (:body get-edn)))
      (is (true? (:dacite.wire/chunk-v1 (wire/read-edn (:body get-edn))))))))

(deftest live-binary-wire-remote-write-back
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url {:binary true})
            wb (client-cache/wrap raw :write-back)
            todos (todo/build wb (todo/seed-items))
            h (types/dacite-hash todos)]
        (is (true? (remote/remote-cas-root! wb nil h)))
        (is (store/s-has? raw h))
        (let [cold (remote/remote-store base-url {:binary true})
              node (store/s-get cold h)]
          (is (some? node))
          (is (= "vector" (types/entry-type node)))))
      (finally
        (stop!)))))

(deftest live-server-todo-domain-over-remote
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      ;; Build on remote store (write-through s-put), then CAS server root.
      (let [remote (remote/remote-store base-url)
            empty (coll/vector-with-store remote)
            t0 (todo/add-todo empty "browser demo item" false)
            t1 (todo/toggle-at t0 0)
            root-h (types/dacite-hash t1)
            cas (remote/remote-cas-root! remote nil root-h)]
        (is (true? cas))
        (is (= root-h (remote/remote-get-root remote)))
        (let [h (remote/remote-get-root remote)
              loaded (d/get-value remote h)]
          (is (= 1 (d/count loaded)))
          (is (= "browser demo item" (todo/title-str (d/nth loaded 0))))
          (is (true? (todo/done? (d/nth loaded 0))))))
      (finally
        (stop!)))))

(deftest parse-store-spec-tokens
  (is (= {:backend :mem} (svc/parse-store-spec "mem")))
  (is (= {:backend :file :path svc/default-file-path}
         (svc/parse-store-spec "file")))
  (is (= {:backend :file :path "target/my-file"}
         (svc/parse-store-spec "file:target/my-file")))
  (is (= {:backend :lmdb :path svc/default-lmdb-path}
         (svc/parse-store-spec "lmdb")))
  (is (= {:backend :lmdb :path "target/my-lmdb"}
         (svc/parse-store-spec "lmdb:target/my-lmdb")))
  (is (= {:backend :file :path svc/default-file-path}
         (svc/parse-store-spec nil))
      "omitted --store defaults to file")
  (is (thrown-with-msg? Exception #"unknown --store"
                        (svc/parse-store-spec "target/bare-path")))
  (is (thrown-with-msg? Exception #"empty path after file:"
                        (svc/parse-store-spec "file:")))
  (is (thrown-with-msg? Exception #"empty path after lmdb:"
                        (svc/parse-store-spec "lmdb:"))))

(deftest make-service-rooted-lmdb-backend
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "dacite-svc-lmdb-" (System/nanoTime))))
        {:keys [rooted close! backend path]}
        (svc/make-service-rooted {:store (str "lmdb:" dir)})]
    (try
      (is (= :lmdb backend))
      (is (= dir path))
      (is (some? rooted))
      (let [r (svc/handle-request rooted "GET" "/root" nil)]
        (is (= 200 (:status r)))
        (is (nil? (:root (wire/read-edn (:body r))))))
      (finally
        (when close! (close!))
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))))))

(deftest lmdb-service-http-node-root-survives-reopen
  ;; Todo-demo protocol over LMDB: PUT/GET node, CAS root, reopen same path.
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "dacite-lmdb-http-" (System/nanoTime))))
        store-spec (str "lmdb:" dir)
        root-hex
        (let [{:keys [rooted close!]} (svc/make-service-rooted {:store store-spec})
              {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
          (try
            (let [remote (remote/remote-store base-url)
                  todos (todo/build remote (todo/seed-items))
                  h (types/dacite-hash todos)
                  hex (store/hash->hex h)]
              (is (true? (remote/remote-cas-root! remote nil h)))
              (is (= h (remote/remote-get-root remote)))
              (is (some? (store/s-get remote h)))
              hex)
            (finally
              (stop!)
              (when close! (close!)))))
        {:keys [rooted close!]} (svc/make-service-rooted {:store store-spec})
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    ;; reopen fresh LMDB env + server on same path
    (try
      (let [remote (remote/remote-store base-url)
            h (store/hex->hash root-hex)
            node (store/s-get remote h)
            loaded (d/get-value remote h)]
        (is (= h (remote/remote-get-root remote)))
        (is (store/s-has? remote h))
        (is (some? node))
        (is (= "vector" (types/entry-type node)))
        (is (pos? (d/count loaded))))
      (finally
        (stop!)
        (when close! (close!))
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))))))
