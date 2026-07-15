(ns dacite.examples.todo
  "A tiny todo app built entirely on the portable Dacite core, written
   against the functional API (dacite.value.api) + an in-memory store.

   The same source runs unchanged on the JVM, babashka, and nbb — it never
   depends on the JVM-only namespaces or the native clojure.lang.*
   collection interfaces."
  (:require [dacite.store :as store]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.hash :as hash]))

(defn add-todo
  "Return a new todos vector with {title, done} appended."
  [todos title done?]
  (d/conj todos (coll/hash-map "title" title "done" done?)))

(defn title-str
  "The title of a todo entry as a native string."
  [todo]
  (apply str (types/realize (d/get todo "title"))))

(defn done?
  [todo]
  (boolean (types/realize (d/get todo "done"))))

(defn build
  "Build a todos vector in st from a seq of [title done?] pairs."
  [st items]
  (store/bind-store st
                    (reduce (fn [todos [title done?]] (add-todo todos title done?))
                            (coll/vector)
                            items)))

(defn render
  "A plain-text listing of the todos."
  [todos]
  (str "todos (" (d/count todos) "):\n"
       (apply str
              (map (fn [t]
                     (str "  [" (if (done? t) "x" " ") "] " (title-str t) "\n"))
                   (d/seq todos)))
       "root: " (hash/hash->hex (types/dacite-hash todos))))

(defn -main [& _]
  (let [st (store/mem-store)
        todos (build st [["write portable host layer" true]
                         ["split the store" true]
                         ["run under babashka" false]
                         ["run under nbb" false]])]
    (println (render todos))))
