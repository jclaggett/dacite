(ns dacite.examples.explorer-test
  "Value explorer: type coverage, seed-once, paged children, lazy expand."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.explorer :as ex]
            [dacite.examples.todo :as todo]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [clojure.set :as set]
            [dacite.value :as v]))

(deftest gallery-covers-every-public-type
  (let [r (v/root-ref (ex/open-mem))
        [g seeded?] (ex/load-or-seed! r)
        types (ex/collect-types g)]
    (is (true? seeded?))
    (is (= "map" (v/value-type g)))
    (is (= ex/public-types types)
        (str "missing " (set/difference ex/public-types types)
             " extra " (set/difference types ex/public-types)))
    (is (= ex/page-me-count (v/count (v/get g "page-me"))))))

(deftest load-or-seed-does-not-overwrite
  (let [r (v/root-ref (ex/open-mem))
        [g _] (ex/load-or-seed! r)
        h (v/dacite-hash g)
        [g2 seeded?] (ex/load-or-seed! r)]
    (is (false? seeded?))
    (is (= h (v/dacite-hash g2)))))

(deftest row-summary-does-not-seq-children
  (let [r (v/root-ref (ex/open-mem))
        [g _] (ex/load-or-seed! r)
        row (ex/row-summary g)
        s (ex/row-summary (v/get g "string"))
        b (ex/row-summary (v/get g "blob"))
        i64 (ex/row-summary (v/get (v/get g "scalars") "i64"))]
    (is (= :map (:kind row)))
    (is (= (v/count g) (:count row)))
    (is (nil? (:preview row)))
    (is (= "string" (:type s)))
    (is (true? (:truncated? s)))
    (is (= ex/string-preview-chars (count (:preview s))))
    (is (> (:count s) ex/string-preview-chars))
    (is (= "blob" (:type b)))
    (is (true? (:truncated? b)))
    (is (= "i64" (:type i64)))
    (is (= "-64" (:native i64)))))

(deftest child-page-bounds-vector
  (let [r (v/root-ref (ex/open-mem))
        [g _] (ex/load-or-seed! r)
        page-me (v/get g "page-me")
        p0 (ex/child-page page-me 0)
        p1 (ex/child-page page-me ex/page-size)]
    (is (= ex/page-me-count (:total p0)))
    (is (= ex/page-size (count (:items p0))))
    (is (false? (:done? p0)))
    (is (= 0 (:label (first (:items p0)))))
    (is (= (dec ex/page-size) (:label (last (:items p0)))))
    (is (= ex/page-size (:offset p1)))
    (is (= ex/page-size (count (:items p1))))
    (is (true? (:done? (ex/child-page page-me 96))))))

(deftest child-page-map-keys-are-values
  (let [r (v/root-ref (ex/open-mem))
        [g _] (ex/load-or-seed! r)
        m (v/get g "map")
        {:keys [items total]} (ex/child-page m 0)]
    (is (= 2 total))
    (is (every? #(v/dacite-value? (:label %)) items))
    (is (some #(= "vector" (v/value-type (:label %))) items))
    (is (some #(= "string" (v/value-type (:label %))) items))))

(deftest remote-expand-page-cheaper-than-full-seq
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [writer (v/root-ref (store/remote-rooted-store base-url))]
        (ex/load-or-seed! writer)
        (stats/reset-stats!)
        (let [cold-page (v/root-ref (store/remote-rooted-store base-url
                                                               {:policy :none}))
              page-delta (:delta
                          (stats/measure
                           (fn []
                             (let [g (v/ref-deref cold-page)
                                   page-me (v/get g "page-me")]
                               (ex/child-page page-me 0)))))
              _ (stats/reset-stats!)
              cold-all (v/root-ref (store/remote-rooted-store base-url
                                                              {:policy :none}))
              all-delta (:delta
                         (stats/measure
                          (fn []
                            (let [g (v/ref-deref cold-all)
                                  page-me (v/get g "page-me")]
                              (count (or (v/seq page-me) ()))))))]
          (is (pos? (:bytes-recv page-delta)))
          (is (< (:bytes-recv page-delta) (:bytes-recv all-delta))
              "first page of page-me must not pull as much as seq of all 128")
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
      (let [w (v/root-ref (store/remote-rooted-store base-url))]
        (v/ref-cas! w nil (todo/build w (todo/seed-items))))
      (stats/reset-stats!)
      (let [cold (v/root-ref (store/remote-rooted-store base-url {:policy :none}))
            d (:delta
               (stats/measure
                (fn []
                  (let [todos (v/ref-deref cold)
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

(def ^:private long-title
  "1893-char title — larger than the 1k pack budget, so the vector/map/string
   are mixed :node walks rather than one root literal."
  (apply str (repeat 1893 \x)))

(deftest explorer-long-title-open-is-few-extra-gets
  ;; Pre-2e (EDN-sized seal): collapsed list 9 req; warm open item 5 +10;
  ;; cold as-str of the title 52. Wire-sized BFS fill must beat those.
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [w (v/root-ref (store/remote-rooted-store base-url))]
        (v/ref-cas! w nil (todo/add-todo (todo/build w (todo/seed-items))
                                         long-title false)))
      (let [r (v/root-ref (store/remote-rooted-store base-url {:policy :none}))
            d-list (:delta
                    (stats/measure
                     (fn []
                       (let [todos (v/ref-deref r)]
                         (ex/row-summary todos)
                         (ex/child-page todos 0)))))
            d-open (:delta
                    (stats/measure
                     (fn []
                       (let [todos (v/ref-deref r)
                             item (v/nth todos 5)]
                         (ex/row-summary item)
                         (doseq [{:keys [label value]} (:items (ex/child-page item 0))]
                           (when (v/dacite-value? label)
                             (ex/row-summary label))
                           (ex/row-summary value))))))]
        (is (pos? (:requests d-list)))
        (is (< (:requests d-open) 10)
            (str "warm open of 1893-char title should not add 10 GETs; got +"
                 (:requests d-open))))
      (stats/reset-stats!)
      (let [cold (v/root-ref (store/remote-rooted-store base-url {:policy :none}))
            d-str (:delta
                   (stats/measure
                    (fn []
                      (todo/title-str (v/nth (v/ref-deref cold) 5)))))]
        (is (< (:requests d-str) 52)
            (str "as-str of 1893-char title should beat pre-2e 52 GETs; got "
                 (:requests d-str))))
      (finally
        (stop!)))))

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
