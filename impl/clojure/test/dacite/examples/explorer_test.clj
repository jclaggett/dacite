(ns dacite.examples.explorer-test
  "Value explorer on the JVM.

   The bodies are `dacite.examples.explorer-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   Four stay JVM only: `remote-expand-page-cheaper-than-full-seq`,
   `explorer-todo-root-is-one-pack-get` and
   `explorer-long-title-open-is-few-extra-gets` (the HTTP service and
   `store/remote`) and `static-explorer-and-todo-index` (the HTTP service
   serving `examples/web`), legacy HTTP."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.explorer-checks :as checks]
            [clojure.java.io :as io]
            [dacite.examples.explorer :as ex]
            [dacite.examples.todo :as todo]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest gallery-covers-every-public-type
  (run-check "gallery-covers-every-public-type"))

(deftest load-or-seed-does-not-overwrite
  (run-check "load-or-seed-does-not-overwrite"))

(deftest row-summary-does-not-seq-children
  (run-check "row-summary-does-not-seq-children"))

(deftest child-page-bounds-vector
  (run-check "child-page-bounds-vector"))

(deftest child-page-map-keys-are-values
  (run-check "child-page-map-keys-are-values"))

(def ^:private long-title
  "1893-char title — larger than the 1k pack budget, so the vector/map/string
   are mixed :node walks rather than one root literal."
  (apply str (repeat 1893 \x)))

(defn- http-no-follow
  "GET url without following redirects. Returns {:status :location}."
  [url]
  (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))]
    (.setInstanceFollowRedirects conn false)
    (.setRequestMethod conn "GET")
    (let [code (.getResponseCode conn)
          loc (.getHeaderField conn "Location")]
      (.disconnect conn)
      {:status code :location loc})))

(deftest remote-expand-page-cheaper-than-full-seq
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [writer (v/root (store/remote-rooted-store base-url))]
        (ex/load-or-seed! writer)
        (stats/reset-stats!)
        (let [cold-page (v/root (store/remote-rooted-store base-url
                                                           {:policy :none}))
              page-delta (:delta
                          (stats/measure
                           (fn []
                             (let [g (v/deref cold-page)
                                   page-me (v/get g "page-me")]
                               (ex/child-page page-me 0)))))
              _ (stats/reset-stats!)
              cold-all (v/root (store/remote-rooted-store base-url
                                                          {:policy :none}))
              all-delta (:delta
                         (stats/measure
                          (fn []
                            (let [g (v/deref cold-all)
                                  page-me (v/get g "page-me")]
                              (count (or (v/seq page-me) ()))))))]
          (is (pos? (:bytes-recv page-delta)))
          (is (< (:bytes-recv page-delta) (:bytes-recv all-delta))
              "first page of page-me must not pull as much as seq of all of them")
          (is (<= (:requests page-delta) (:requests all-delta)))))
      (finally
        (stop!)))))

(deftest explorer-todo-root-is-one-pack-get
  ;; Same data as /app/: a seed todo vector packs as one literal.
  ;; Explorer row-summary + first page + opening item 0 stay in that
  ;; neighborhood (value walk on local mem; HTTP shipped the data).
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [w (v/root (store/remote-rooted-store base-url))]
        (v/cas! w nil (todo/build w (todo/seed-items))))
      (stats/reset-stats!)
      (let [cold (v/root (store/remote-rooted-store base-url {:policy :none}))
            d (:delta
               (stats/measure
                (fn []
                  (let [todos (v/deref cold)
                        item0 (v/nth todos 0)]
                    (ex/row-summary todos)
                    (ex/child-page todos 0)
                    (ex/row-summary item0)
                    (doseq [{:keys [label value]} (:items (ex/child-page item0 0))]
                      (when (v/dacite-value? label)
                        (ex/row-summary label))
                      (ex/row-summary value))))))]
        (is (pos? (:bytes-recv d)))
        (is (<= (:requests d) 3)
            (str "todo root should arrive as one pack GET after GET /root; got "
                 (:requests d) " requests")))
      (finally
        (stop!)))))

(deftest explorer-long-title-open-is-few-extra-gets
  ;; Pre-2e (EDN-sized seal): collapsed list 9 req; warm open item 5 +10;
  ;; cold as-str of the title 52. Wire-sized BFS fill must beat those.
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [w (v/root (store/remote-rooted-store base-url))]
        (v/cas! w nil (todo/add-todo (todo/build w (todo/seed-items))
                                     long-title false)))
      (let [r (v/root (store/remote-rooted-store base-url {:policy :none}))
            d-list (:delta
                    (stats/measure
                     (fn []
                       (let [todos (v/deref r)]
                         (ex/row-summary todos)
                         (ex/child-page todos 0)))))
            d-open (:delta
                    (stats/measure
                     (fn []
                       (let [todos (v/deref r)
                             item (v/nth todos 5)]
                         (ex/row-summary item)
                         (doseq [{:keys [label value]} (:items (ex/child-page item 0))]
                           (when (v/dacite-value? label)
                             (ex/row-summary label))
                           (ex/row-summary value))))))]
        (is (pos? (:requests d-list)))
        (is (<= (:requests d-open) 3)
            (str "title preview should pack in one neighborhood GET, not 64 leaf GETs; got +"
                 (:requests d-open))))
      (stats/reset-stats!)
      (let [cold (v/root (store/remote-rooted-store base-url {:policy :none}))
            d-str (:delta
                   (stats/measure
                    (fn []
                      (todo/title-str (v/nth (v/deref cold) 5)))))]
        (is (<= (:requests d-str) 40)
            (str "as-str of 1893-char title should beat pre-2e 52 GETs; got "
                 (:requests d-str))))
      (finally
        (stop!)))))

(deftest static-explorer-and-todo-index
  (let [rooted (svc/make-demo-rooted)
        static (io/file "../../examples/web")
        {:keys [base-url stop!]} (svc/start-server! {:port 0
                                                     :rooted rooted
                                                     :static-dir static
                                                     :throttle false})]
    (try
      (let [explorer-slash (slurp (str base-url "/app/explorer/"))
            noslash (http-no-follow (str base-url "/app/explorer"))
            todo (slurp (str base-url "/app/"))
            app-noslash (http-no-follow (str base-url "/app"))]
        (is (re-find #"Dacite Value Explorer" explorer-slash))
        (is (re-find #"/app/explorer/js/main.js" explorer-slash)
            "script URL must be root-absolute so /app/explorer (no slash) still works")
        (is (= 301 (:status noslash)))
        (is (re-find #"/app/explorer/$" (:location noslash)))
        (is (re-find #"Dacite Todo" todo))
        (is (= 301 (:status app-noslash)))
        (is (re-find #"/app/$" (:location app-noslash))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.explorer-test)))))
               "every-check-has-a-deftest"
               "remote-expand-page-cheaper-than-full-seq"
               "explorer-todo-root-is-one-pack-get"
               "explorer-long-title-open-is-few-extra-gets"
               "static-explorer-and-todo-index"))
      "examples/explorer_checks.cljc and this file list the same tests"))
