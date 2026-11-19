(ns dacite.cli.store-cljs-test
  "nbb checks for the store `npx dacite` opens (`dacite.cli.store`,
   `dacite.cli.peer/open-store!`, docs/design/packaging.md §P3). Run from
   the repo root:

     npm run dacite:store-test

   The data dir from env, platform, and home; the engine marker and the
   fallback warning; `LOCK` (fresh, a live pid refused, a dead pid taken
   over with the warning, a lost takeover, mode 0600 on POSIX); and a
   store that keeps its value across a stop and a start, on the file
   engine and on LMDB when it loads. Node only: no JVM side."
  (:require [clojure.string :as str]
            [dacite.cli.peer :as peer]
            [dacite.cli.store :as cs]
            [dacite.rooted :as rooted]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.check :as check :refer [is testing]]
            [dacite.value :as v]))

(def ^:private fs (js/require "fs"))
(def ^:private os (js/require "os"))
(def ^:private node-path (js/require "path"))

(def ^:private dirs (atom []))

(defn- tmp-dir
  []
  (let [d (.mkdtempSync fs (.join node-path (.tmpdir os) "dacite-cli-store-"))]
    (swap! dirs conj d)
    d))

(defn- slurp [p] (.readFileSync fs p "utf8"))

(defn- mode [p] (bit-and (.-mode (.statSync fs p)) 511))

(def ^:private posix? (not= "win32" (.-platform js/process)))

(def ^:private dead-pid
  "A pid `alive?` stubs report dead."
  999991)

(defn- alive-except-dead [pid] (not= pid dead-pid))

(defn- warnings [] (let [a (atom [])] [a #(swap! a conj %)]))

(def lmdb-error (cs/lmdb-loads))

(defn- reopen-keeps-the-value
  "Open a store dir, hold the welcome value, put a second value and make
   it the root, stop, start again: the root and its entries are there."
  [lmdb-err]
  (let [dir (.join node-path (tmp-dir) "nested" "store")
        [warned warn] (warnings)
        o1 (cs/open! dir {:lmdb-error lmdb-err :warn warn})
        engine (:engine o1)
        held1 (peer/hold! (:content o1) (:cell o1) (:empty? o1))
        x (v/map (:content o1) "kept" (v/vector (:content o1) 1 2 3))
        h (v/hash x)]
    (is (:empty? o1) "a new store dir is empty")
    (is (= (if lmdb-err :file :lmdb) engine))
    (is (= (if lmdb-err 1 0) (count @warned)) "a warning only when lmdb did not load")
    (is (.existsSync fs dir) "--store dir created with its parents")
    (is (= 15 (:entries held1)) "the welcome value was put")
    (rooted/rc-put! (:cell o1) h)
    ((:close o1))
    (is (not (.existsSync fs (.join node-path dir cs/lock-name))) "close removes LOCK")
    (let [[warned2 warn2] (warnings)
          o2 (cs/open! dir {:lmdb-error lmdb-err :warn warn2})
          held2 (peer/hold! (:content o2) (:cell o2) (:empty? o2))]
      (try
        (is (= engine (:engine o2)))
        (is (not (:empty? o2)))
        (is (= [] @warned2) "no warning on a marked store")
        (is (= h (:value held2)) "the printed value is the root")
        (is (= (count (gc/mark-reachable (:content o2) h)) (:entries held2)))
        (let [y (v/get-value (:content o2) h)]
          (is (some? y) "the root value reads back")
          (is (= (v/hash (v/vector (store/mem-store) 1 2 3)) (some-> y (v/get "kept") v/hash))
              "with its parts"))
        (finally ((:close o2)))))))

(def checks
  {"data-dir-follows-the-environment"
   (fn [_]
     (let [dd cs/data-dir]
       (testing "DACITE_HOME wins on every platform"
         (is (= "/d/h" (dd {:env {"DACITE_HOME" "/d/h" "XDG_DATA_HOME" "/x"} :platform "linux" :home "/home/u"})))
         (is (= "/d/h" (dd {:env {"DACITE_HOME" "/d/h"} :platform "darwin" :home "/Users/u"})))
         (is (= "D:\\dh" (dd {:env {"DACITE_HOME" "D:\\dh" "LOCALAPPDATA" "C:\\L"} :platform "win32" :home "C:\\Users\\u"}))))
       (testing "an empty DACITE_HOME is unset"
         (is (= "/home/u/.local/share/dacite" (dd {:env {"DACITE_HOME" ""} :platform "linux" :home "/home/u"}))))
       (testing "macOS"
         (is (= "/Users/u/Library/Application Support/Dacite" (dd {:env {"XDG_DATA_HOME" "/x"} :platform "darwin" :home "/Users/u"}))))
       (testing "Linux and other POSIX"
         (is (= "/x/dacite" (dd {:env {"XDG_DATA_HOME" "/x"} :platform "linux" :home "/home/u"})))
         (is (= "/home/u/.local/share/dacite" (dd {:env {} :platform "linux" :home "/home/u"})))
         (is (= "/home/u/.local/share/dacite" (dd {:env {"XDG_DATA_HOME" "rel/x"} :platform "linux" :home "/home/u"}))
             "a relative XDG_DATA_HOME is ignored")
         (is (= "/home/u/.local/share/dacite" (dd {:env {"XDG_DATA_HOME" ""} :platform "freebsd" :home "/home/u"}))))
       (testing "Windows: local, not roaming"
         (is (= "C:\\Users\\u\\AppData\\Local\\Dacite"
                (dd {:env {"LOCALAPPDATA" "C:\\Users\\u\\AppData\\Local" "APPDATA" "C:\\Users\\u\\AppData\\Roaming"}
                     :platform "win32" :home "C:\\Users\\u"})))
         (is (= "C:\\Users\\u\\AppData\\Local\\Dacite" (dd {:env {} :platform "win32" :home "C:\\Users\\u"}))
             "no LOCALAPPDATA"))
       (testing "the default store is <data dir>/store"
         (is (str/ends-with? (cs/default-store-dir) (str (.-sep node-path) "store"))))))

   "the-engine-marker-and-the-fallback-warning"
   (fn [_]
     (let [marker #(slurp (.join node-path % cs/marker-name))
           write! #(.writeFileSync fs (.join node-path %1 cs/marker-name) %2)
           reason #(try (cs/choose-engine! %1 %2) nil (catch :default e (:reason (ex-data e))))]
       (testing "an empty dir gets lmdb when it loads, marked with its format version"
         (let [d (tmp-dir)]
           (is (= {:engine :lmdb :warning nil} (cs/choose-engine! d nil)))
           (is (= "lmdb 2\n" (marker d)))))
       (testing "an empty dir falls back to the file store with a warning"
         (let [d (tmp-dir)
               r (cs/choose-engine! d "Cannot find module 'lmdb'")]
           (is (= :file (:engine r)))
           (is (re-find #"^lmdb did not load \(Cannot find module 'lmdb'\); using the file store$"
                        (str (:warning r))))
           (is (= "file 1\n" (marker d)))))
       (testing "the marker line parses to engine and version"
         (is (= "lmdb 2" (cs/marker-line "lmdb")))
         (is (= "file 1" (cs/marker-line "file")))
         (is (= {:engine "lmdb" :version 2} (cs/parse-marker "lmdb 2\n")))
         (is (= {:engine "file" :version 1} (cs/parse-marker " file 1 ")))
         (is (= {:engine "rocksdb" :version 7} (cs/parse-marker "rocksdb 7")))
         (doseq [bad ["lmdb" "file" "" "lmdb  2" "lmdb 2 x" "lmdb two" "lmdb 02" "lmdb 0" "LMDB 2" "lmdb\n2"]]
           (is (nil? (cs/parse-marker bad)) (pr-str bad))))
       (testing "a marked dir keeps its engine"
         (let [d (tmp-dir)]
           (write! d "file 1\n")
           (is (= {:engine :file :warning nil} (cs/choose-engine! d nil)) "file stays file when lmdb loads")
           (is (= "file 1\n" (marker d)) "the marker is not rewritten")))
       (testing "an lmdb store without lmdb is refused"
         (let [d (tmp-dir)]
           (write! d "lmdb 2\n")
           (is (check/throws? #"is an lmdb store, and lmdb did not load: gone" #(cs/choose-engine! d "gone")))
           (is (= :wrong-engine (reason d "gone")))))
       (testing "a newer or older format version is refused"
         (let [d (tmp-dir)]
           (write! d "lmdb 3\n")
           (is (check/throws? #"^store .* uses lmdb format 3; this dacite supports lmdb 2$" #(cs/choose-engine! d nil)))
           (is (= :unsupported-version (reason d nil))))
         (let [d (tmp-dir)]
           (write! d "file 2\n")
           (is (check/throws? #"uses file format 2; this dacite supports file 1$" #(cs/choose-engine! d nil))))
         (let [d (tmp-dir)]
           (write! d "lmdb 1\n")
           (is (check/throws? #"uses lmdb format 1; this dacite supports lmdb 2$" #(cs/choose-engine! d nil)))))
       (testing "an unknown engine is refused"
         (let [d (tmp-dir)]
           (write! d "rocksdb 1\n")
           (is (check/throws? #"unknown engine \"rocksdb\"; this dacite supports lmdb 2 and file 1" #(cs/choose-engine! d nil)))
           (is (= :unknown-engine (reason d nil)))))
       (testing "a bare engine name or garbage is an invalid marker"
         (doseq [text ["lmdb\n" "file\n" "" "lmdb two\n" "{:engine :lmdb}"]]
           (let [d (tmp-dir)]
             (write! d text)
             (is (check/throws? #"has an invalid ENGINE marker .*; expected \"lmdb 2\" or \"file 1\""
                                #(cs/choose-engine! d nil))
                 (pr-str text))
             (is (= :invalid-marker (reason d nil)))
             (is (= text (marker d)) "left as it was"))))
       (testing "an unmarked dir with engine data gets the versioned marker for that engine"
         (let [d (tmp-dir)]
           (.mkdirSync fs (.join node-path d "lmdb"))
           (is (check/throws? #"lmdb did not load" #(cs/choose-engine! d "gone")))
           (is (not (.existsSync fs (.join node-path d cs/marker-name))) "no marker written on refusal")
           (is (= {:engine :lmdb :warning nil} (cs/choose-engine! d nil)))
           (is (= "lmdb 2\n" (marker d))))
         (let [d (tmp-dir)]
           (.mkdirSync fs (.join node-path d "file"))
           (is (= {:engine :file :warning nil} (cs/choose-engine! d nil)))
           (is (= "file 1\n" (marker d)))))
       (testing "open! refuses a wrong-engine or wrong-version dir and leaves no LOCK"
         (doseq [text ["lmdb 2\n" "file 9\n"]]
           (let [d (tmp-dir)]
             (write! d text)
             (is (check/throws? #"lmdb did not load|file format 9" #(cs/open! d {:lmdb-error "gone" :warn identity})))
             (is (not (.existsSync fs (.join node-path d cs/lock-name)))))))))

   "lock-fresh-live-and-dead"
   (fn [_]
     (let [lock #(.join node-path % cs/lock-name)]
       (testing "a fresh LOCK holds our pid, mode 0600 on POSIX"
         (let [d (tmp-dir)
               release (cs/acquire-lock! d {:pid 4242 :alive? alive-except-dead})]
           (is (= "4242\n" (slurp (lock d))))
           (when posix? (is (= 384 (mode (lock d))) "0600"))
           (release)
           (is (not (.existsSync fs (lock d))) "release removes it")))
       (testing "a live pid is refused, naming the pid and how to clear it"
         (let [d (tmp-dir)]
           (.writeFileSync fs (lock d) (str (.-pid js/process) "\n"))
           (is (check/throws? (re-pattern (str "in use by pid " (.-pid js/process)
                                               "; if no dacite is running, delete .*LOCK"))
                              #(cs/acquire-lock! d {:pid 4242})))
           (is (= (str (.-pid js/process) "\n") (slurp (lock d))) "LOCK left alone")))
       (testing "EPERM counts as alive"
         (let [d (tmp-dir)
               eperm (fn [_ _] (throw (doto (js/Error. "eperm") (aset "code" "EPERM"))))
               kill (.-kill js/process)]
           (.writeFileSync fs (lock d) "1\n")
           (set! (.-kill js/process) eperm)
           (try
             (is (cs/pid-alive? 1))
             (is (check/throws? #"in use by pid 1" #(cs/acquire-lock! d {:pid 4242})))
             (finally (set! (.-kill js/process) kill)))))
       (testing "a dead pid (ESRCH) is taken over with the warning"
         (let [d (tmp-dir)
               [warned warn] (warnings)]
           (.writeFileSync fs (lock d) (str dead-pid "\n"))
           (let [release (cs/acquire-lock! d {:pid 4242 :alive? alive-except-dead :warn warn})]
             (is (= [(str "taking over a stale LOCK from pid " dead-pid)] @warned))
             (is (= "4242\n" (slurp (lock d))))
             (when posix? (is (= 384 (mode (lock d))) "0600 after the rename"))
             (is (= [cs/lock-name] (vec (.readdirSync fs d))) "no LOCK.<pid>.tmp left")
             (release))))
       (testing "the real ESRCH: pid-alive? on a pid that cannot exist"
         (is (false? (cs/pid-alive? 2147483646))))
       (testing "a LOCK with no pid is stale"
         (let [d (tmp-dir)
               [warned warn] (warnings)]
           (.writeFileSync fs (lock d) "")
           ((cs/acquire-lock! d {:pid 4242 :warn warn}))
           (is (= ["taking over a stale LOCK from pid unknown"] @warned))))
       (testing "another peer renames first: the read-back fails"
         (let [d (tmp-dir)]
           (.writeFileSync fs (lock d) (str dead-pid "\n"))
           (is (check/throws? #"taken over by pid 5151 first"
                              #(cs/acquire-lock! d {:pid 4242 :alive? alive-except-dead :warn identity
                                                    :after-rename (fn [p] (.writeFileSync fs p "5151\n"))})))
           (is (= "5151\n" (slurp (lock d))) "the winner's LOCK stays")))
       (testing "release leaves another peer's LOCK"
         (let [d (tmp-dir)
               release (cs/acquire-lock! d {:pid 4242})]
           (.writeFileSync fs (lock d) "5151\n")
           (release)
           (is (= "5151\n" (slurp (lock d))))))
       (testing "open! takes LOCK: a second open of the same dir is refused"
         (let [d (tmp-dir)
               o (cs/open! d {:lmdb-error "forced" :warn identity})]
           (try
             (is (check/throws? #"in use by pid" #(cs/open! d {:lmdb-error "forced" :warn identity})))
             (finally ((:close o))))))))

   "file-store-keeps-its-value-across-a-restart"
   (fn [_] (reopen-keeps-the-value "forced off for the check"))

   "lmdb-store-keeps-its-value-across-a-restart"
   (fn [_]
     (if lmdb-error
       (println "  lmdb did not load, skipped:" lmdb-error)
       (reopen-keeps-the-value nil)))

   "memory-store-holds-the-welcome-value"
   (fn [_]
     (let [{:keys [engine dir content cell empty? close]} (peer/open-store! {:memory true})
           held (peer/hold! content cell empty?)]
       (is (= :memory engine))
       (is (nil? dir))
       (is (= 15 (:entries held)))
       (is (= (v/hash (peer/welcome-value (store/mem-store))) (:value held)))
       (close)))

   "a-non-empty-store-without-a-root-is-not-written"
   (fn [_]
     (let [d (tmp-dir)
           o (cs/open! d {:lmdb-error "forced" :warn identity})
           _ (v/string (:content o) "something else")
           files #(count (filter (fn [f] (str/ends-with? f ".edn"))
                                 (js->clj (.readdirSync fs (:path o) #js {:recursive true}))))
           before (files)
           held (peer/hold! (:content o) (:cell o) false)]
       (is (= before (files)) "no welcome entries added")
       (is (= 0 (:entries held)) "the welcome value is not there to reach")
       ((:close o))))})

(defn -main
  [& _]
  (let [rows (try (mapv #(check/run-one checks {} % 1500) (sort (keys checks)))
                  (finally
                    (doseq [d @dirs] (.rmSync fs d #js {:recursive true :force true}))))
        bad (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) rows)]
    (println (str "dacite cli store checks: " (count rows) " tests, " (count bad)
                  " problems, slowest " (check/slowest rows)))
    (doseq [p bad] (println "  FAIL" p))
    (if (seq bad)
      (js/process.exit 1)
      (println "dacite cli store tests passed"))))
