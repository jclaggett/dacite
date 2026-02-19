(ns dacite.types
  "Dacite type system.
   
   All Dacite values are [type, data] tuples where:
   - type is a keyword identifying the type
   - data is the type-specific payload
   
   This namespace defines:
   - Accessors for extracting type and data
   - The dacite-size multimethod for computing value sizes
   - Built-in primitive type implementations
   
   To add a new type, extend dacite-size:
   (defmethod dacite-size :my-type [[_ data]] ...)")

;; =============================================================================
;; Value accessors
;; =============================================================================

(defn dacite-type
  "Get the type keyword from a Dacite value [type, data]."
  [[type-kw _]]
  type-kw)

(defn dacite-data
  "Get the data from a Dacite value [type, data]."
  [[_ data]]
  data)

;; =============================================================================
;; Size multimethod
;; =============================================================================

(defmulti dacite-size
  "Get the size in bytes of a Dacite value [type, data].
   
   Dispatches on type keyword. To add a new type:
   (defmethod dacite-size :my-type [[_ data]] ...)
   
   Collections with :measure in data return (:size-bytes measure).
   Primitives should define explicit methods."
  dacite-type)

;; Default: check for :measure (collections), otherwise estimate
(defmethod dacite-size :default [[_ data]]
  (if-let [measure (:measure data)]
    (:size-bytes measure)
    ;; Fallback: serialize and measure (not ideal, but safe)
    (count (.getBytes (pr-str data) "UTF-8"))))

;; =============================================================================
;; Null
;; =============================================================================

(defmethod dacite-size :null [_] 0)

;; =============================================================================
;; Boolean
;; =============================================================================

(defmethod dacite-size :bool [_] 1)

;; =============================================================================
;; Signed integers
;; =============================================================================

(defmethod dacite-size :i8 [_] 1)
(defmethod dacite-size :i16 [_] 2)
(defmethod dacite-size :i32 [_] 4)
(defmethod dacite-size :i64 [_] 8)
(defmethod dacite-size :i128 [_] 16)
(defmethod dacite-size :i256 [_] 32)

;; =============================================================================
;; Unsigned integers
;; =============================================================================

(defmethod dacite-size :u8 [_] 1)
(defmethod dacite-size :u16 [_] 2)
(defmethod dacite-size :u32 [_] 4)
(defmethod dacite-size :u64 [_] 8)
(defmethod dacite-size :u128 [_] 16)
(defmethod dacite-size :u256 [_] 32)

;; =============================================================================
;; Floating point
;; =============================================================================

(defmethod dacite-size :f32 [_] 4)
(defmethod dacite-size :f64 [_] 8)

;; =============================================================================
;; Character (Unicode code point, UTF-8 encoded)
;; =============================================================================

(defmethod dacite-size :char [[_ ch]]
  (count (.getBytes (str ch) "UTF-8")))

;; =============================================================================
;; Strings (UTF-8 byte count)
;; =============================================================================

(defmethod dacite-size :string [[_ data]]
  (count (.getBytes ^String data "UTF-8")))

;; =============================================================================
;; Collections (cached size-bytes from construction)
;; =============================================================================

(defmethod dacite-size :vector [[_ data]]
  (:size-bytes data 0))

(defmethod dacite-size :map [[_ data]]
  (:size-bytes data 0))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Get type and data
  (dacite-type [:i64 42])   ;; => :i64
  (dacite-data [:i64 42])   ;; => 42

  ;; Get size
  (dacite-size [:i64 42])   ;; => 8
  (dacite-size [:i8 1])     ;; => 1
  (dacite-size [:bool true]) ;; => 1

  ;; Collections with :measure
  (dacite-size [:ft/deep {:measure {:count 10 :size-bytes 80}}])  ;; => 80
  )
