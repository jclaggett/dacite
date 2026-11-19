(ns dacite.cli.run-checks
  "nbb checks for the run file and port reuse (`dacite.cli.run`,
   docs/design/packaging.md §P2), and the 0700 data dir. Run with the
   store checks by `npm run dacite:store-test`
   (`dacite.cli.store-cljs-test`). Every path is under a temp dir;
   `DACITE_HOME` is pointed there while a check needs the default."
  (:require [dacite.cli.peer :as peer]
            [dacite.cli.run :as run]
            [dacite.store.check :as check :refer [is testing]]))

(def ^:private fs (js/require "fs"))
(def ^:private os (js/require "os"))
(def ^:private node-path (js/require "path"))

(def ^:private dirs (atom []))

(defn cleanup!
  "Remove the temp dirs these checks made."
  []
  (doseq [d @dirs] (.rmSync fs d #js {:recursive true :force true}))
  (reset! dirs []))

(defn- tmp-dir
  []
  (let [d (.mkdtempSync fs (.join node-path (.tmpdir os) "dacite-cli-run-"))]
    (swap! dirs conj d)
    d))

(def ^:private posix? (not= "win32" (.-platform js/process)))

(defn- mode [p] (bit-and (.-mode (.statSync fs p)) 511))

(defn- with-dacite-home
  "Run `f` with `DACITE_HOME` set to `home`, then put it back."
  [home f]
  (let [env (.-env js/process)
        had (.hasOwnProperty env "DACITE_HOME")
        old (aget env "DACITE_HOME")]
    (aset env "DACITE_HOME" home)
    (try (f)
         (finally
           (if had (aset env "DACITE_HOME" old) (js-delete env "DACITE_HOME"))))))

(def ^:private pid (.-pid js/process))

(defn- info
  "A run file body for `port` held by `pid`, serving `store`."
  [port pid store]
  {:dacite "0.0.0-check" :proto run/proto :pid pid :port port
   :url (str "http://127.0.0.1:" port "/app/")
   :signal (str "ws://127.0.0.1:" port "/signal")
   :room "dacite-room" :token "tok"
   :open (str "http://127.0.0.1:" port "/app/browser/#token=tok")
   :store store :engine (if store "lmdb" "memory") :value "ab12"})

(def checks
  {"data-dir-and-run-dir-are-private"
   (fn [_]
     (let [base (tmp-dir)]
       (testing "a new data dir and its parents are 0700 on POSIX"
         (let [d (.join node-path base "a" "b" "data")]
           (run/ensure-private-dir! d)
           (is (.existsSync fs d))
           (when posix?
             (is (= 448 (mode d)) "0700")
             (is (= 448 (mode (.join node-path base "a"))) "a created parent too"))))
       (testing "an existing data dir is left as it is"
         (let [d (.join node-path base "existing")]
           (.mkdirSync fs d #js {:mode 493})
           (.chmodSync fs d 493)
           (run/ensure-private-dir! d)
           (when posix? (is (= 493 (mode d)) "0755 kept"))))
       (testing "run/ is created 0700, and tightened to 0700 when it exists"
         (let [d (.join node-path base "data2")]
           (is (= (.join node-path d "run") (run/ensure-run-dir! d)))
           (when posix? (is (= 448 (mode (.join node-path d "run")))))
           (.chmodSync fs (.join node-path d "run") 493)
           (run/ensure-run-dir! d)
           (when posix? (is (= 448 (mode (.join node-path d "run"))) "tightened"))))
       (testing "the default store makes the data dir 0700; --store leaves the data dir alone"
         (let [home (.join node-path base "home-default")]
           (with-dacite-home home
             (fn []
               (let [o (peer/open-store! {:lmdb-error "forced" :warn identity})]
                 (try
                   (is (= (.join node-path home "store") (:dir o)))
                   (when posix? (is (= 448 (mode home)) "data dir 0700"))
                   (finally ((:close o))))))))
         (let [home (.join node-path base "home-unused")
               mine (.join node-path base "my-store")]
           (with-dacite-home home
             (fn []
               (let [o (peer/open-store! {:dir mine :lmdb-error "forced" :warn identity})]
                 (try
                   (is (not (.existsSync fs home)) "no data dir made for --store")
                   (when posix? (is (not= 448 (mode mine)) "the --store dir keeps the umask mode"))
                   (finally ((:close o)))))))))))

   "the-run-file-is-written-atomically-and-removed-when-ours"
   (fn [_]
     (let [d (tmp-dir)
           p (run/run-path d 8790)]
       (is (= (.join node-path d "run" "8790.json") p))
       (is (nil? (run/read-run-file d 8790)) "none yet")
       (testing "contents, in a fixed key order"
         (is (= p (run/write-run-file! d (assoc (info 8790 4242 "/s") :extra "dropped"))))
         (is (= (info 8790 4242 "/s") (run/read-run-file d 8790)) "every field read back, extra keys dropped")
         (is (= ["dacite" "proto" "pid" "port" "url" "signal" "room" "token" "open" "store" "engine" "value"]
                (vec (js-keys (js/JSON.parse (.readFileSync fs p "utf8"))))))
         (when posix? (is (= 384 (mode p)) "0600"))
         (is (= ["8790.json"] (vec (.readdirSync fs (run/run-dir d)))) "no temp file left"))
       (testing "a memory peer's store is null"
         (run/write-run-file! d (info 8791 4242 nil))
         (is (nil? (:store (run/read-run-file d 8791))))
         (is (re-find #"\"store\": null" (.readFileSync fs (run/run-path d 8791) "utf8"))))
       (testing "a rewrite replaces it"
         (run/write-run-file! d (info 8790 5151 "/s"))
         (is (= 5151 (:pid (run/read-run-file d 8790))))
         (when posix? (is (= 384 (mode p)))))
       (testing "removed only when it holds our pid"
         (is (false? (run/remove-run-file! d 8790 4242)))
         (is (.existsSync fs p) "another peer's run file stays")
         (is (true? (run/remove-run-file! d 8790 5151)))
         (is (not (.existsSync fs p)))
         (is (false? (run/remove-run-file! d 8790 5151)) "already gone"))
       (testing "an unreadable run file"
         (.writeFileSync fs p "{not json")
         (is (string? (:invalid (run/read-run-file d 8790))))
         (.writeFileSync fs p "{\"port\": 8790}")
         (is (= {:invalid "no pid"} (run/read-run-file d 8790)))
         (is (false? (run/remove-run-file! d 8790 5151)) "not removed: it is not ours"))))

   "reuse-prints-the-run-file-or-refuses"
   (fn [_]
     (let [d (tmp-dir)
           store (.join node-path d "store")
           alive (fn [p] (not= p 999991))
           go #(run/reuse (merge {:data-dir d :port 8790 :peer-pid pid :alive? alive
                                  :store {:dir store}}
                                 %))]
       (testing "no run file"
         (is (re-find #"^dacite \(pid \d+\) is running on port 8790, but there is no run file .*8790\.json \(another user's peer, or a stale file\)\. Stop it or pass --port\.$"
                      (str (:error (go {}))))))
       (testing "our peer, our store: the URL again"
         (run/write-run-file! d (info 8790 pid store))
         (is (= {:lines [(str "dacite already running http://127.0.0.1:8790/app/ (pid " pid ")")
                         (str "store lmdb " store)
                         "value ab12"
                         "open http://127.0.0.1:8790/app/browser/#token=tok"
                         "paste dacite://local#urn:dacite:ab12"]}
                (go {})))
         (is (:lines (go {:store {:dir (str store (.-sep node-path))}})) "the same dir spelled with a trailing separator"))
       (testing "the default store matches through DACITE_HOME"
         (with-dacite-home d #(is (:lines (go {:store {}})))))
       (testing "another store, or memory, is refused"
         (is (re-find #"serves the store .*store, not the store .*other\. Stop it or pass --port\.$"
                      (str (:error (go {:store {:dir (.join node-path d "other")}})))))
         (is (re-find #"serves the store .*, not an in-memory store\."
                      (str (:error (go {:store {:memory true}}))))))
       (testing "a memory peer matches --memory only"
         (run/write-run-file! d (info 8790 pid nil))
         (is (= "store memory" (second (:lines (go {:store {:memory true}})))))
         (is (re-find #"serves an in-memory store, not the store "
                      (str (:error (go {}))))))
       (testing "a dead pid is stale"
         (run/write-run-file! d (info 8790 999991 store))
         (is (re-find #"^the run file .* is stale: pid 999991 is not running, and pid \d+ holds port 8790\. Stop it or pass --port\.$"
                      (str (:error (go {}))))))
       (testing "a live pid that is not the peer on the port is stale"
         (run/write-run-file! d (info 8790 1 store))
         (is (re-find #"is stale: it names pid 1, and pid \d+ holds port 8790\."
                      (str (:error (go {}))))))
       (testing "an unreadable run file"
         (.writeFileSync fs (run/run-path d 8790) "garbage")
         (is (re-find #"its run file .* is unreadable" (str (:error (go {}))))))
       (testing "the real pid-alive? for a pid that cannot exist"
         (.writeFileSync fs (run/run-path d 8790) (js/JSON.stringify (clj->js (info 8790 2147483646 store))))
         (is (re-find #"pid 2147483646 is not running"
                      (str (:error (run/reuse {:data-dir d :port 8790 :peer-pid pid :store {:dir store}}))))))))})

(defn run-all
  "Run every check: rows of {:name :ms :problems}."
  []
  (try (mapv #(check/run-one checks {} % 1500) (sort (keys checks)))
       (finally (cleanup!))))
