(ns salt.lang
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.math.numeric-tower :as numeric-tower]
            [clojure.set :as set]
            [clojure.string :as string]))

(defn- CONSTANT-f [x]
  (when (symbol? x)
    `(do
       (when (string/includes? ~(str x) "-")
         (throw (RuntimeException. (str "cannot include '-' characters in constant identifiers: "
                                        ~(str x)))))
       (def ~x nil))))

(defmacro CHANGED- [vars]
  `true)

(defmacro UNCHANGED [vars]
  `true)

(defmacro CONSTANT [& constants]
  `(do
     ~@(map CONSTANT-f constants)
     nil))

(defmacro A [[x s] body]
  `(every? #(= % true) (map (fn [~x]
                              ~body) ~s)))

(defmacro E [[x s] body]
  `(let [f# (fn [~x]
              ~body)
         choices# (filter #(let [r# (f# %)]
                             (when (true? r#)
                               %)) ~s)]
     (if (pos? (count choices#))
       true
       false)))

(defmacro ASSUME [body]
  `(do (when (not ~body))
       nil))

;;

(defn get*
  "Redefine to be 1 based to match TLA+"
  [c k]
  (if (map? c)
    (get c k)
    (if (integer? k)
      (get c (dec k))
      (get c k))))

(defn assoc-in*
  [c [k & ks] v]
  (let [k' (if (map? c)
             k
             (if (integer? k)
               (dec k)
               k))]
    (if ks
      (assoc c k'
             (assoc-in* (get* c k) ks v))
      (assoc c k' v))))

;;

(defn EXCEPT-f [m path new-value-f]
  (let [current-value (get-in m path)]
    (assoc-in* m path (new-value-f current-value))))

(defmacro EXCEPT [m & bindings]
  (let [[path new-value & more] bindings
        result (if (and (seq? new-value)
                        (= 'fn* (first new-value)))
                 `(EXCEPT-f ~m ~path ~new-value)
                 `(EXCEPT-f ~m ~path (fn [_#] ~new-value)))]
    (if (seq? more)
      `(EXCEPT ~result ~@more)
      `~result)))

(defn- VARIABLE-f [v]
  (when (symbol? v)
    `(do
       (when (string/includes? ~(str v) "-")
         (throw (RuntimeException. (str "cannot include '-' characters in variable identifiers: "
                                        ~(str v)))))
       (def ~v)
       (def ~(symbol (str v "'"))))))

(defmacro VARIABLE [& vars]
  `(do
     (def ~'VARS- '~(vec (->> vars
                              (filter symbol?)
                              (map #(with-meta % {:ns (.name *ns*)})))))
     ~@(map VARIABLE-f vars)
     nil))

(defmacro always-
  ([f]
   `~f)
  ([f vars]
   `[~f ~vars]))

(defmacro eventually- [f]
  `[~f])

(defmacro WF [vars [f]]
  `[~f])

(defmacro SF [vars [f]]
  `[~f])

(defn DOMAIN
  "Redefined variant of 'keys' to produce sets and to work on vectors"
  [x]
  (cond
    (vector? x) (set (range 1 (inc (count x))))
    (map? x) (set (keys x))
    :default (throw (RuntimeException. (str "cannot take domain of: " x)))))

(defn- prime-variable? [x]
  (and (symbol? x)
       (.endsWith (name x) "'")))

(def ^:dynamic *success*)

(defn clause-to-binding [[op a b :as x]]
  (when-not (= '= op)
    (throw (RuntimeException. (str "Expected an equality predicate " x))))
  [a b])

(defn clauses-to-bindings [clauses]
  (let [result (-> (mapcat clause-to-binding clauses)
                   vec)
        symbols (->> result
                     (partition 2)
                     (map first))
        all-symbols? (every? symbol? symbols)
        all-prime? (every? prime-variable? symbols)
        all-not-prime? (not (some prime-variable? symbols))]
    (when-not (and all-symbols?
                   (or all-prime?
                       all-not-prime?))
      (throw (RuntimeException. (if all-symbols?
                                  (str "all clauses need to reference prime or non-prime variables " clauses)
                                  (str "all clauses need to reference variables as first operand " clauses)))))
    result))

(defmacro CHOOSE [[x s] body]
  `(first (filter (fn [~x]
                    ~body) ~s)))

(defn X [a b]
  (set (for [x a
             y b]
         [x y])))

;;;;

(defn- seq-to-map [x]
  (apply hash-map x))

(defn- map-to-vector-if-needed [m]
  (let [ks (keys m)]
    (if (every? integer? ks)
      (let [min-k (apply min ks)
            max-k (apply max ks)]
        (if (and (= 1 min-k)
                 (= (count ks) max-k))
          (vec (map #(get m %) (range min-k (inc max-k))))
          m))
      m)))

(map-to-vector-if-needed {:a 1}) {:a 1}
(map-to-vector-if-needed {1 "a"}) ["a"]
(map-to-vector-if-needed {1 "a" 2 "b"}) ["a" "b"]

(defn set-of-maps-from-sets [A B]
  (if (empty? A)
    #{[]}
    (let [result (let [result (->> (for [a A]
                                     (for [b B]
                                       [a b]))
                                   (reduce (fn [x y]
                                             (if (= x :start)
                                               (map seq-to-map y)
                                               (for [a x
                                                     b (map seq-to-map y)]
                                                 (merge a b)))) :start))]
                   (if (= result :start)
                     #{}
                     (set result)))
          result (map map-to-vector-if-needed result)]
      (set result))))

(defn set-of-maps-f [args]
  (loop [[[k s] & r] (partition 2 args)
         result #{{}}]
    (if k
      (recur r (for [m result
                     v s]
                 (assoc m k v)))
      (set result))))

(defmacro maps- [& args]
  (if (= (count args) 1)
    `(set-of-maps-f ~@args)
    `(set-of-maps-from-sets ~@args)))

;;;;

(defmacro => [p q]
  (boolean (or (not p)
               q)))

(defmacro <=> [p q]
  (boolean (or (and p q)
               (and (not p) (not q)))))

(defn tuple-to-map [t]
  (let [[v k & more] t
        result {k v}]
    (if (zero? (count more))
      result
      (loop [result result
             [k & more] more]
        (if (zero? (count more))
          {k result}
          (recur {k result} more))))))

(defmacro fm- [bindings body]
  (let [vars (vec (map first (partition 2 bindings)))]
    `(reduce (fn [a# b#]
               (merge-with merge a# b#)) {}
             (for ~bindings
               (tuple-to-map (reverse (conj ~vars
                                            (apply (fn ~vars
                                                     ~body) ~vars))))))))

(defmacro defm- [m-name bindings body]
  `(def ~m-name (fm- ~bindings ~body)))

(defn UNION
  "Combine many sets into one."
  [s]
  (reduce set/union #{} s))

(defn line-
  "Insert a line separator into the spec."
  [])

(defn expt [& args]
  (apply numeric-tower/expt args))

;; redefined core functions

(defn range*
  "Redefined to include end value to match TLA+"
  [a b]
  (when (or (nil? a)
            (nil? b))
    (throw (RuntimeException. (str "Cannot compute range* of: " [a b]))))
  (set (range a (inc b))))

(defn map*
  [f coll]
  (fmap f coll))

(defn every?* [p v]
  (when-not (and (vector? v)
                 (set p))
    (throw (RuntimeException. (str "unsupported types for every?*" [p v]))))
  (every? p v))

(defn mod* [x y]
  (when (not (pos? y))
    (throw (str "Second arg to mod* must be positive")))
  (mod x y))

;; set functions

(defn union [& args]
  (apply set/union args))

(defn subset? [x y]
  (set/subset? x y))

(defn subset-proper? [x y]
  (and (set/subset? x y)
       (not (= x y))))

(defn superset? [x y]
  (set/superset? x y))

(defn superset-proper? [x y]
  (and (set/superset? x y)
       (not (= x y))))

(defn intersection [& args]
  (apply set/intersection args))

(defn difference [& args]
  (apply set/difference args))

(defn select [& args]
  (apply set/select args))

;;

(comment "

Naming scheme used for identifiers in salt.

'clojure': indicates whether the identifier already existed in the clojure ecosystem
'changed': indicates whether the semantics of the identifier change in salt
'tla+'   : indicates whether the identifier exists in tla+

clojure changed tla+
-------------------------------------------------------------------------------------------------------------
  X               X    : foo
  X                    : foo
  X      X        X    : foo+ (+ indicates that the semantics have been changed to match the target language)
  X      X             : foo* (combines both + and -)
                       : foo- (- indicates that when going to the target language, this will not be present)
                  X    : foo

")
