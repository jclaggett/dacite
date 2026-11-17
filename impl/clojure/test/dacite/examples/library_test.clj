(ns dacite.examples.library-test
  "Library on the JVM.

   The bodies are `dacite.examples.library-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   `static-library-index` stays JVM only: it uses the HTTP service (legacy
   HTTP)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.library-checks :as checks]
            [clojure.java.io :as io]
            [dacite.service :as svc]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest contents-list-is-not-chapters
  (run-check "contents-list-is-not-chapters"))

(deftest library-root-predicate
  (run-check "library-root-predicate"))

(deftest pages-in-chapter-covers-text
  (run-check "pages-in-chapter-covers-text"))

(deftest empty-shape
  (run-check "empty-shape"))

(deftest ingest-shares-epub-blob
  (run-check "ingest-shares-epub-blob"))

(deftest duplicate-ingest-is-identity
  (run-check "duplicate-ingest-is-identity"))

(deftest title-index-is-sorted
  (run-check "title-index-is-sorted"))

(deftest page-is-slice-of-text
  (run-check "page-is-slice-of-text"))

(deftest add-epub-without-catalog
  (run-check "add-epub-without-catalog"))

(deftest seed-plate-is-a-png-the-reader-can-show
  (run-check "seed-plate-is-a-png-the-reader-can-show"))

(deftest seed-has-three-chapters
  (run-check "seed-has-three-chapters"))

(deftest file-reopen
  (run-check "file-reopen"))

(defn- http-no-follow
  [url]
  (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))]
    (.setInstanceFollowRedirects conn false)
    (.setRequestMethod conn "GET")
    (let [code (.getResponseCode conn)
          loc (.getHeaderField conn "Location")]
      (.disconnect conn)
      {:status code :location loc})))

(deftest static-library-index
  (let [rooted (svc/make-demo-rooted)
        static (io/file "../../examples/web")
        {:keys [base-url stop!]} (svc/start-server! {:port 0
                                                     :rooted rooted
                                                     :static-dir static
                                                     :throttle false})]
    (try
      (let [slash (slurp (str base-url "/app/library/"))
            noslash (http-no-follow (str base-url "/app/library"))]
        (is (re-find #"Dacite Library" slash))
        (is (re-find #"/app/library/js/main.js" slash)
            "script URL must be root-absolute")
        (is (= 301 (:status noslash)))
        (is (re-find #"/app/library/$" (:location noslash))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.library-test)))))
               "every-check-has-a-deftest"
               "static-library-index"))
      "examples/library_checks.cljc and this file list the same tests"))
