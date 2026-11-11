(ns dacite.store.check
  "A small `is` for checks that run on the JVM and on nbb.

   A check is a function of `env` (the host map, see `dacite.store-checks`).
   Inside it, `is` records a problem string when its form is false, and
   `testing` names the part of the check that follows. `run-one` runs a
   check and returns its problems and time. Nothing here is clojure.test:
   the JVM deftest that wraps a check asserts once that it had no problems
   and once that it was quick, so a deftest's assertion count is fixed.

   Used by the pack, chunk, lens, and rate-limit checks.")

(def ^:dynamic *problems*
  "An atom of problem strings while a check runs."
  nil)

(def ^:dynamic *context*
  "The `testing` labels around the current form, outermost first."
  [])

(defn show
  "`pr-str` of `x`, or a note when `x` cannot be printed."
  [x]
  (try (pr-str x)
       (catch #?(:clj Throwable :cljs :default) _
         (str "<unprintable " (type x) ">"))))

(defn record!
  "Record a problem when `ok` is false. Returns `ok`. `values` is nil or a
   function of no arguments for the detail, called only on a problem."
  ([ok form msg] (record! ok form msg nil))
  ([ok form msg values]
   (when-not ok
     (let [p (str (when (seq *context*) (str (apply str (interpose " " *context*)) ": "))
                  (when msg (str msg ": "))
                  (pr-str form)
                  (when values (str " " (values))))]
       (if *problems*
         (swap! *problems* conj p)
         (throw (ex-info (str "check failed outside run-one: " p) {})))))
   ok))

(defmacro is
  "Record a problem when `form` is false. `(is (= expected actual))` also
   shows both values."
  ([form] `(is ~form nil))
  ([form msg]
   (if (and (seq? form) (= '= (first form)) (= 3 (count form)))
     `(let [e# ~(second form)
            a# ~(nth form 2)]
        (record! (= e# a#) '~form ~msg
                 (fn [] (str "expected " (show e#) ", got " (show a#)))))
     `(record! (boolean ~form) '~form ~msg))))

(defmacro testing
  "Run `body` with `label` added to the context of its problems."
  [label & body]
  `(binding [*context* (conj *context* ~label)]
     ~@body))

(defn throws?
  "True when `(f)` throws, and its message matches `re` when given."
  ([f] (throws? nil f))
  ([re f]
   (try (f) false
        (catch #?(:clj Throwable :cljs :default) e
          (if re (boolean (re-find re (str (ex-message e)))) true)))))

(defn throws-info?
  "True when `(f)` throws an ExceptionInfo (`ex-info`). Any other throw
   propagates, so the check reports it."
  [f]
  (try (f) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) _ true)))

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-one
  "Run the check `(get checks name)` on `env`: {:name :ms :problems}. A
   check that throws is a problem, and one over `max-ms` is a problem."
  [checks env name]
  (let [f (get checks name)
        t0 (now-ms)
        problems (binding [*problems* (atom [])
                           *context* []]
                   (try (f env)
                        @*problems*
                        (catch #?(:clj Throwable :cljs :default) e
                          (conj @*problems*
                                (str "threw " (ex-message e) " " (pr-str (ex-data e)))))))
        ms (- (now-ms) t0)]
    {:name name :ms ms
     :problems (cond-> problems
                 (> ms max-ms) (conj (str "took " ms " ms, over " max-ms)))}))
