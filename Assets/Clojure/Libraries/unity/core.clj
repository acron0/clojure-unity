(ns unity.core
  (:require [clojure.string :as string]
            [unity.reflect :as r]
            unity.messages)
  (:import [UnityEngine
            MonoBehaviour
            GameObject
            PrimitiveType]))

;; ============================================================
;; defcomponent 
;; ============================================================

(defmacro defcomponent
  "Defines a new component."
  [name fields & methods] 
  (require 'unity.messages)
  `(defcomponent*
     ~name
     ;; make all fields mutable
     ~(vec (map #(vary-meta % assoc :unsynchronized-mutable true) fields))
     ~@(concat 
         (let [forms (take-while list? methods)]
           ;; add protocol declaration for known unity messages
           (interleave (map #(symbol (str "unity.messages/I" (first %))) forms)
                       ;; wrap method bodies in typehinted let bindings
                       (map (fn [[name args & body]]
                              (list name args
                                `(let ~(vec (flatten (map (fn [typ arg]
                                              [(vary-meta arg assoc :tag typ) arg])
                                            (unity.messages/messages name)
                                            (drop 1 args))))
                                   ~@body)))
                         forms)))
         (drop-while list? methods))))

(defmacro defleaked [var]
  `(def ~(symbol (name var))
     (var-get (find-var '~var))))

(defleaked clojure.core/validate-fields)
(defleaked clojure.core/parse-opts+specs)
(defleaked clojure.core/build-positional-factory)

(defn- emit-defclass* 
  "Do not use this directly - use defcomponent"
  [tagname name extends assem fields interfaces methods]
  (assert (and (symbol? extends) (symbol? assem)))
  (let [classname (with-meta
                    (symbol
                      (str (namespace-munge *ns*) "." name))
                    (meta name))
        interfaces (conj interfaces 'clojure.lang.IType)]
    `(defclass*
       ~tagname ~classname
       ~extends ~assem
       ~fields 
       :implements ~interfaces 
       ~@methods))) 

(defmacro defcomponent*
  [name fields & opts+specs]
  (validate-fields fields)
  (let [gname name 
        [interfaces methods opts] (parse-opts+specs opts+specs)
        ns-part (namespace-munge *ns*)
        classname (symbol (str ns-part "." gname))
        hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))
        [field-args over] (split-at 20 fields)]
    `(let []
       ~(emit-defclass*
          name
          gname
          'UnityEngine.MonoBehaviour
          'UnityEngine
          (vec hinted-fields)
          (vec interfaces)
          methods)
       (import ~classname)
       ~(build-positional-factory gname classname fields)
       ~classname)))

;; ============================================================
;; get-component
;; ============================================================

(defn- camels-to-hyphens [s]
  (string/replace s #"([a-z])([A-Z])" "$1-$2"))

(defn- dedup-by [f coll]
  (map peek (vals (group-by f coll))))

(defn- some-2
  "Uses reduced, should be faster + less garbage + more general than clojure.core/some"
  [pred coll]
  (reduce #(when (pred %2) (reduced %2)) nil coll))

(defn- in? [x coll]
  (boolean (some-2 #(= x %) coll)))

(defn- type-name? [x]
  (boolean
    (and (symbol? x)
      (when-let [y (resolve x)]
        (instance? System.MonoType y)))))

(defn- type-of-local-reference [x env]
  (assert (contains? env x))
  (let [lclb ^clojure.lang.CljCompiler.Ast.LocalBinding (env x)]
    (when (.get_HasClrType lclb)
      (.get_ClrType lclb))))

(defn- type? [x]
  (instance? System.MonoType x))

(defn- tag-type [x]
  (when-let [t (:tag (meta x))]
    (cond ;; this does seem kind of stupid. Can we really tag things
          ;; with mere symbols as types?
      (type? t) t
      (type-name? t) (resolve t))))

(defn- type-of-reference [x env]
  (or (tag-type x) ; tagged symbol
      (when (contains? env x) (type-of-local-reference x env)) ; local
      (when (symbol? x) (tag-type (resolve x))))) ; reference to tagged var, or whatever 

;; really ought to be testing for arity as well
(defn- type-has-method? [t mth]
  (in? (symbol mth) (map :name (r/methods t :ancestors true))))

;; maybe we should be passing full method sigs around rather than
;; method names. 
(defn- known-implementer-reference? [x method-name env]
  (boolean
    (when-let [tor (type-of-reference x env)]
      (type-has-method? tor method-name))))

(defn- raise-args [[head & rst]]
  (let [gsyms (repeatedly (count rst) gensym)]
    `(let [~@(interleave gsyms rst)]
       ~(cons head gsyms))))

(defn- raise-non-symbol-args [[head & rst]]
  (let [bndgs (zipmap 
                (remove symbol? rst)
                (repeatedly gensym))]
    `(let [~@(mapcat reverse bndgs)]
       ~(cons head (replace bndgs rst)))))

(defmacro get-component* [obj t]
  (if (not-every? symbol? [obj t])
    (raise-non-symbol-args
      (list 'unity.core/get-component* obj t))
    (cond
      (contains? &env t)
      `(.GetComponent ~obj ~t)
      (and
        (known-implementer-reference? obj 'GetComponent &env)
        (type-name? t))
      `(.GetComponent ~obj (~'type-args ~t))
      :else
      `(.GetComponent ~obj ~t))))

(defn get-component
  "Returns the component of Type type if the game object has one attached, nil if it doesn't."
  {:inline (fn [gameobject type]
             (list 'unity.core/get-component* gameobject type))
   :inline-arities #{2}}
  [gameobject type]
  (.GetComponent gameobject type))

(comment (defmacro single-typearg-generic [name doc]
  (let [cljname     (-> name str camels-to-hyphens string/lower-case symbol)
        macroname   (symbol (str cljname "*"))
        nsmacroname (symbol (str (.Name *ns*) "/" cljname "*"))]
    `(do
       (defmacro ~nsmacroname [obj# t#]
         (if (not-every? symbol? [obj# t#])
           (raise-non-symbol-args
             (list ~nsmacroname obj# t#))
           (cond
             (contains? ~'&env t#)
             (quote (. (unquote obj#) ~name (unquote t#)))
             (and
               (known-implementer-reference? obj# (quote ~name) ~'&env)
               (type-name? t#))
             (quote (. (unquote obj#) ~name (~'type-args (unquote t#))))
             :else
             (quote (. obj# ~name t#)))))

       (defn ~cljname
          ~doc
          {:inline (fn [~'gameobject ~'type]
                     (~nsmacroname
                        ~'gameobject
                        ~'type))
          :inline-arities #{2}}
          [~'gameobject ~'type]
          (. ~'gameobject ~name ~'type))))))

(comment (pprint (macroexpand-1 '(single-typearg-generic GetComponentInChildren "foo")))
(set! *print-meta* true))

(comment (single-typearg-generic GetComponentInChildren "foo"))

;; ============================================================
;; wrappers
;; ============================================================

(defmacro defwrapper
  "Wrap static methods of C# classes"
  ([class]
   `(do ~@(map (fn [m]
          `(defwrapper
             ~class
             ~(symbol (.Name m))
             ~(str "No documentation for " class "/" (.Name m))))
        (->>
          (.GetMethods
            (resolve class)
            (enum-or BindingFlags/Public BindingFlags/Static))
          (remove #(or (.IsSpecialName %) (.IsGenericMethod %)))))))
  ([class method docstring]
   `(defwrapper
     ~(symbol (string/lower-case
                (camels-to-hyphens (str method))))
     ~class
     ~method
     ~docstring))
  ([name class method docstring & body]
   `(defn ~name
      ~docstring
      ~@(->> (.GetMethods
               (resolve class)
               (enum-or BindingFlags/Public BindingFlags/Static))
             (filter #(= (.Name %) (str method)))
             (remove #(.IsGenericMethod %))
             (dedup-by #(.Length (.GetParameters %)))
             (map (fn [m]
                    (let [params (map #(symbol (.Name %)) (.GetParameters m))]
                      (list (vec params)
                            `(~(symbol (str class "/" method)) ~@params))))))
      ~@body)))

(defwrapper instantiate UnityEngine.Object Instantiate
  "Clones the object original and returns the clone."
  ([^UnityEngine.Object obj ^UnityEngine.Vector3 pos]
   (UnityEngine.Object/Instantiate obj pos Quaternion/identity)))

(defn create-primitive [prim]
  (if (= PrimitiveType (type prim))
    (GameObject/CreatePrimitive prim)
    (GameObject/CreatePrimitive (case prim
                                  :sphere   PrimitiveType/Sphere
                                  :capsule  PrimitiveType/Capsule
                                  :cylinder PrimitiveType/Cylinder
                                  :cube     PrimitiveType/Cube
                                  :plane    PrimitiveType/Plane
                                  :quad     PrimitiveType/Quad))))

(defwrapper UnityEngine.Object Destroy
  "Removes a gameobject, component or asset.")

(defwrapper UnityEngine.Object DestroyImmediate
  "Destroys the object obj immediately. You are strongly recommended to use Destroy instead.")

(defwrapper UnityEngine.Object DontDestroyOnLoad
  "Makes the object target not be destroyed automatically when loading a new scene.")

(defwrapper object-typed UnityEngine.Object FindObjectOfType
  "Returns the first active loaded object of Type type.")

(defwrapper objects-typed UnityEngine.Object FindObjectsOfType
  "Returns a list of all active loaded objects of Type type.")

(defwrapper object-named GameObject Find
  "Finds a game object by name and returns it.")

(defn objects-named
  "Finds a game objects by name and returns them."
  [^System.String name]
  (->> (objects-typed GameObject) (filter #(= (.name %) name))))

(defwrapper object-tagged GameObject FindWithTag
  "Returns one active GameObject tagged tag. Returns null if no GameObject was found.")

(defwrapper objects-tagged GameObject FindGameObjectsWithTag
  "Returns a list of active GameObjects tagged tag. Returns empty array if no GameObject was found.")