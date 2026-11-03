(ns dacite.kernel-vectors
  "Known answers for exactly the fuse kernel boundary (`dacite.hash/kernel`,
   docs/design/fuse-sl2.md, \"The kernel boundary\"), checked against any map of
   the five kernel functions: the in-tree one (JVM and nbb), or a wrapper around
   a WASM or native module. All inputs and outputs are hex strings.

   The vectors are `:kernel` in fixtures/fuse-sl2/vectors.edn:

     :state-mul     [{:a hex :b hex :product hex}]
     :bytes->state  [{:bytes hex :state hex}]
     :ref->state    [{:ref hex :state hex}]
     :state->ref    [{:state hex :ref hex}]
     :state-valid?  [{:state hex :valid true|false}]
     :refused       [{:fn kw :args [hex ...]}]   ; must throw, not return

   `problems` returns a vector of strings, empty when the kernel agrees.")

(defn- refused?
  [f args]
  (try (apply f args) false
       (catch #?(:clj Throwable :cljs :default) _ true)))

(defn problems
  [kernel {:keys [state-mul bytes->state ref->state state->ref state-valid? refused]}]
  (let [check (fn [what f args expected]
                (let [got (try (apply f args)
                               (catch #?(:clj Throwable :cljs :default) e (str "threw " (ex-message e))))]
                  (when-not (= expected got)
                    [(str "kernel " what " " (pr-str (mapv #(if (and (string? %) (> (count %) 24)) (str (subs % 0 24) "...") %) args))
                          ": got " (pr-str (if (and (string? got) (> (count got) 40)) (str (subs got 0 40) "...") got))
                          ", expected " (pr-str (if (and (string? expected) (> (count expected) 40)) (str (subs expected 0 40) "...") expected)))])))]
    (vec
     (concat
      (mapcat (fn [{:keys [a b product]}] (check ":state-mul" (:state-mul kernel) [a b] product)) state-mul)
      (mapcat (fn [{:keys [bytes state]}] (check ":bytes->state" (:bytes->state kernel) [bytes] state)) bytes->state)
      (mapcat (fn [{:keys [ref state]}] (check ":ref->state" (:ref->state kernel) [ref] state)) ref->state)
      (mapcat (fn [{:keys [state ref]}] (check ":state->ref" (:state->ref kernel) [state] ref)) state->ref)
      (mapcat (fn [{:keys [state valid]}] (check ":state-valid?" (:state-valid? kernel) [state] valid)) state-valid?)
      (mapcat (fn [{f :fn args :args}]
                (when-not (refused? (get kernel f) args)
                  [(str "kernel " f " " (pr-str (mapv #(subs % 0 (min 24 (count %))) args)) " was not refused")]))
              refused)))))
