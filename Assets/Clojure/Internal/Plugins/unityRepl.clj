(ns unityRepl
  (:refer-clojure :exclude [with-bindings])
  (:require [clojure.main :as main]))

(defn env-map []
  {:*ns* *ns*
   :*warn-on-reflection* *warn-on-reflection*
   :*math-context* *math-context*
   :*print-meta* *print-meta*
   :*print-length* *print-length*
   :*print-level* *print-level*
   :*data-readers* *data-readers*
   :*default-data-reader-fn* *default-data-reader-fn*
   :*command-line-args* *command-line-args*
   :*unchecked-math* *unchecked-math*
   :*assert* *assert*})

(defn update-repl-env [repl-env]
  (swap! repl-env #(merge % (env-map))))

(defmacro with-bindings
  "Executes body in the context of thread-local bindings for several vars
  that often need to be set!"
  [repl-env & body]
  `(let [re# ~repl-env]
     (when (not (instance? clojure.lang.Atom re#))
       (throw (ArgumentException. "repl-env must be an atom")))
     (let [e# @re#]
       (binding [*ns* (:*ns* e#)
                 *warn-on-reflection* (:*warn-on-reflection* e#)
                 *math-context* (:*math-context* e#)
                 *print-meta* (:*print-meta* e#)
                 *print-length* (:*print-length* e#)
                 *print-level* (:*print-level* e#)
                 *data-readers* (:*data-readers* e#)
                 *default-data-reader-fn* (:*default-data-reader-fn* e#)
                 *command-line-args* (:*command-line-args* e#)
                 *unchecked-math* (:*unchecked-math* e#)
                 *assert* (:*assert* e#)]
         ~@body))))

(defn repl-eval-print [repl-env frm]
  (with-bindings repl-env
    (with-out-str
      (binding [*err* *out*] ;; not sure about this
        (print
          (let [res (eval frm)]
            (update-repl-env repl-env)
            res))))))

(def default-repl-env
  (binding [*ns* (find-ns 'user)]
    (doto (atom {}) (unityRepl/update-repl-env))))

(defn repl-eval-string 
  ([s] (repl-eval-string s *out*))
  ([s out]
     (binding [*out* out]
       (repl-eval-print default-repl-env (read-string s)))))
