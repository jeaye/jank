(ns com.jeaye.jank.inference.core
  (:require [clojure.string]
            [orchestra.core :refer [defn-spec]]
            [com.jeaye.jank.log :refer [pprint]]
            [com.jeaye.jank.parse.spec :as parse.spec]))

(def type-counter* (atom 0))
(defn next-typename! []
  {::type-kind ::unknown
   ::name (str "t" (swap! type-counter* inc))})

(defn scope-lookup [scope scope-path bind]
  (loop [scope-path scope-path]
    (let [local-scope (get-in scope scope-path)]
      (cond
        (empty? scope-path)
        nil

        (nil? local-scope)
        (recur (pop scope-path))

        :else
        (if-some [names (::names local-scope)]
          (if-some [found (get names bind)]
            found
            (recur (pop scope-path)))
          (recur (pop scope-path)))))))

(let [scope-path-key-counter* (atom 0)]
  (defn next-scope-path-key! [base]
    (keyword (str (name base) "#" (swap! scope-path-key-counter* inc)))))

(defn scope-add-binding
  ([scope scope-path bind-name bind-type]
   (update-in scope (conj scope-path ::names) assoc bind-name bind-type))
  ([scope scope-path bind-name bind-type value-type]
   (-> (scope-add-binding scope scope-path bind-name bind-type)
       (update-in (conj scope-path ::types) assoc bind-name value-type))))

(defmulti assign-typenames
  (fn [expression scope scope-path]
    (::parse.spec/kind expression)))

(defmethod assign-typenames :constant
  [expression scope scope-path]
  (let [typename (case (::parse.spec/type expression)
                   :nil "nil"
                   :boolean "boolean"
                   :integer "integer"
                   :real "real"
                   :string "string"
                   :regex "regex"
                   :map "map" ; TODO: Parameterize?
                   :vector "vector" ; TODO: Parameterize?
                   :set "set" ; TODO: Parameterize?
                   )]
    {::expression (assoc expression
                         ::type {::type-kind ::single
                                 ::name typename}
                         ::scope-path scope-path)
     ::scope scope}))

(defmethod assign-typenames :binding
  [expression scope scope-path]
  (let [ident-type (next-typename!)
        binding-name (-> expression ::parse.spec/identifier ::parse.spec/name)
        scope+binding (if (-> expression ::parse.spec/value some?)
                        ; TODO: Maybe this is needed for recursion. Maybe not.
                        (scope-add-binding scope
                                           scope-path
                                           binding-name
                                           ident-type
                                           ; value-type
                                           ident-type)
                        (scope-add-binding scope
                                           scope-path
                                           binding-name
                                           ident-type))
        scope+value (if-some [value (::parse.spec/value expression)]
                      (let [nested-path (conj scope-path :binding binding-name)
                            res (assign-typenames value scope+binding nested-path)]
                        (update res ::scope (fn [scope]
                                              (scope-add-binding scope
                                                                 scope-path
                                                                 binding-name
                                                                 ident-type
                                                                 (-> res ::expression ::type)))))
                      {::scope scope+binding})]
    {::expression (-> (if-some [v (::expression scope+value)]
                        (assoc expression ::parse.spec/value v)
                        expression)
                      (assoc-in [::parse.spec/identifier ::type] ident-type)
                      (assoc ::type ident-type
                             ::scope-path scope-path))
     ::scope (::scope scope+value)}))

(defmethod assign-typenames :identifier
  [expression scope scope-path]
  ; TODO: Unresolved is ok; check again at the end.
  (if-some [ident-type (scope-lookup scope scope-path (::parse.spec/name expression))]
    {::expression (assoc expression
                         ::type ident-type
                         ::scope-path scope-path)
     ::scope scope}
    (do
      (pprint {:scope scope
               :scope-path scope-path})
      (assert false (str "error: unknown identifier " (::parse.spec/name expression))))))

(defmethod assign-typenames :fn
  [expression scope scope-path]
  (let [fn-scope-path (conj scope-path (next-scope-path-key! :fn))
        scope+params (reduce (fn [acc param]
                               (let [res (assign-typenames param (::scope acc) fn-scope-path)]
                                 (-> (assoc acc ::scope (::scope res))
                                     (update ::parse.spec/parameters conj (::expression res)))))
                             {::parse.spec/parameters []
                              ::scope scope}
                             (::parse.spec/parameters expression))
        body (assign-typenames (::parse.spec/body expression) (::scope scope+params) fn-scope-path)
        fn-type {::type-kind ::function
                 ::parameter-types (map ::type (::parse.spec/parameters scope+params))
                 ::return-type (-> body ::expression ::parse.spec/return ::type)}]
    {::expression (assoc expression
                         ::type fn-type
                         ::scope-path scope-path
                         ::parse.spec/parameters (::parse.spec/parameters scope+params)
                         ::parse.spec/body (::expression body))
     ::scope (::scope body)}))

(defmethod assign-typenames :do
  [expression scope scope-path]
  (let [body-scope-path (conj scope-path (next-scope-path-key! :body))
        body (reduce (fn [acc body-expr]
                       (let [res (assign-typenames body-expr (::scope acc) body-scope-path)]
                         (-> (assoc acc ::scope (::scope res))
                             (update ::parse.spec/body conj (::expression res)))))
                     {::parse.spec/body []
                      ::scope scope}
                     (::parse.spec/body expression))
        return (assign-typenames (::parse.spec/return expression)
                                 (::scope body)
                                 body-scope-path)]
    {::expression (assoc expression
                         ::type (-> return ::expression ::type)
                         ::scope-path scope-path
                         ::parse.spec/body (::parse.spec/body body)
                         ::parse.spec/return (::expression return))
     ::scope (::scope return)}))

(defmethod assign-typenames :let
  [expression scope scope-path]
  (let [let-scope-path (conj scope-path (next-scope-path-key! :let))
        scope+bindings (reduce (fn [acc bind]
                                 (let [res (assign-typenames bind (::scope acc) let-scope-path)]
                                   (-> (assoc acc ::scope (::scope res))
                                       (update ::parse.spec/bindings conj (::expression res)))))
                               {::parse.spec/bindings []
                                ::scope scope}
                               (::parse.spec/bindings expression))
        scope+body (assign-typenames (::parse.spec/body expression)
                                     (::scope scope+bindings)
                                     let-scope-path)]
    {::expression (assoc expression
                         ::type (-> scope+body ::expression ::type)
                         ::scope-path scope-path
                         ::parse.spec/bindings (::parse.spec/bindings scope+bindings)
                         ::parse.spec/body (::expression scope+body))
     ::scope (::scope scope+body)}))

(defmethod assign-typenames :application
  [expression scope scope-path]
  (let [fn-name-expr (assign-typenames (::parse.spec/value expression) scope scope-path)
        arguments (reduce (fn [acc arg-expr]
                            (let [res (assign-typenames arg-expr (::scope acc) scope-path)]
                              (-> (assoc acc ::scope (::scope res))
                                  (update ::parse.spec/arguments conj (::expression res)))))
                          {::parse.spec/arguments []
                           ::scope (::scope fn-name-expr)}
                       (::parse.spec/arguments expression))]
    {::expression (assoc expression
                         ::type (next-typename!)
                         ::scope-path scope-path
                         ::parse.spec/value (::expression fn-name-expr)
                         ::parse.spec/arguments (::parse.spec/arguments arguments))
     ::scope (::scope arguments)}))

(defmethod assign-typenames :if
  [expression scope scope-path]
  (let [condition (assign-typenames (::parse.spec/condition expression) scope scope-path)
        scope-path-key (next-scope-path-key! :if)
        then (assign-typenames (::parse.spec/then expression)
                               (::scope condition)
                               (conj scope-path scope-path-key :then))
        else (assign-typenames (::parse.spec/else expression)
                               (::scope then)
                               (conj scope-path scope-path-key :else))]
    {::expression (assoc expression
                         ::type (next-typename!)
                         ::scope-path scope-path
                         ::parse.spec/condition (::expression condition)
                         ::parse.spec/then (::expression then)
                         ::parse.spec/else (::expression else))
     ::scope (::scope else)}))

(defmethod assign-typenames :default
  [expression scope scope-path]
  {::expression expression
   ::scope scope})

(defmulti generate-equations
  (fn [expression equations scope]
    (::parse.spec/kind expression)))

(defmethod generate-equations :constant
  [expression equations scope]
  equations)

(defmethod generate-equations :binding
  [expression equations scope]
  (if-some [value (::parse.spec/value expression)]
    (conj (generate-equations value equations scope)
          [(::type expression) (::type value)])
    equations))

(defmethod generate-equations :identifier
  [expression equations scope]
  equations)

(defmethod generate-equations :fn
  [expression equations scope]
  (let [equations (reduce (fn [acc param-expr]
                            (generate-equations param-expr acc scope))
                          equations
                          (::parse.spec/parameters expression))
        side {::type-kind ::function
              ::parameter-types (map ::type (::parse.spec/parameters expression))
              ::return-type (-> expression ::parse.spec/body ::parse.spec/return ::type)}]
    (-> (generate-equations (::parse.spec/body expression) equations scope)
        (conj [(::type expression) side]))))

(defmethod generate-equations :do
  [expression equations scope]
  (let [equations (reduce (fn [acc body-expr]
                            (generate-equations body-expr acc scope))
                          equations
                          (::parse.spec/body expression))
        equations (generate-equations (::parse.spec/return expression) equations scope)]
    (conj equations [(::type expression) (-> expression ::parse.spec/return ::type)])))

(defmethod generate-equations :let
  [expression equations scope]
  (let [equations (reduce (fn [acc bind]
                            (generate-equations bind acc scope))
                          equations
                          (::parse.spec/bindings expression))
        equations (generate-equations (::parse.spec/body expression) equations scope)]
    (conj equations [(::type expression) (-> expression ::parse.spec/body ::type)])))

(defmethod generate-equations :application
  [expression equations scope]
  (let [fn-side {::type-kind ::function
                 ::parameter-types (map ::type (::parse.spec/arguments expression))
                 ::return-type (::type expression)}
        equations (reduce (fn [acc arg-expr]
                            (generate-equations arg-expr acc scope))
                          equations
                          (::parse.spec/arguments expression))]
    (conj equations
          [(-> expression ::parse.spec/value ::type) fn-side])))

(defmethod generate-equations :if
  [expression equations scope]
  (let [equations (generate-equations (::parse.spec/condition expression) equations scope)
        equations (reduce (fn [acc branch-expr]
                            (generate-equations branch-expr acc scope))
                          equations
                          [(::parse.spec/then expression)
                           (::parse.spec/else expression)])]
    (conj equations
          [(-> expression ::parse.spec/condition ::type) {::type-kind ::single
                                                          ::name "boolean"}]
          [(::type expression) (-> expression ::parse.spec/then ::type)]
          [(::type expression) (-> expression ::parse.spec/else ::type)])))

(defmethod generate-equations :default
  [expression equations scope]
   equations)

(defn occurs?
  "Returns whether or not `v` occurs within `typ`."
  [v typ substitutions]
  ;(println "occurs?" v typ substitutions)
  (if (= v typ)
    true
    (if-let [sub (and (= ::unknown (::type-kind typ)) (get substitutions (::name typ)))]
      (occurs? v sub substitutions)
      (if (= ::function (::type-kind typ))
        (boolean (or (occurs? v (::return-type typ) substitutions)
                     (some #(occurs? v % substitutions) (::parameter-types typ))))
        false))))

(declare unify)
(defn unify-variable [v typ substitutions]
  ;(println "unify-variable" v typ substitutions)
  (if-some [sub (get substitutions (::name v))]
    (unify sub typ substitutions)
    (if-let [sub (and (= ::unknown (::type-kind typ)) (get substitutions (::name typ)))]
      (unify v sub substitutions)
      (if (occurs? v typ substitutions)
        ; Self-recurring types can't be unified.
        (do
          (println "error:" v "occurs within" typ)
          nil)
        (assoc substitutions (::name v) typ)))))

(defn unify [left right substitutions]
  ;(println "unify" left right substitutions)
  (cond
    ; Error propogation.
    (nil? substitutions)
    nil

    ; Already concrete.
    (= left right)
    substitutions

    (= ::unknown (::type-kind left))
    (unify-variable left right substitutions)

    (= ::unknown (::type-kind right))
    (unify-variable right left substitutions)

    (and (= ::function (::type-kind left))
         (= ::function (::type-kind right)))
    (if-not (= (-> left ::parameter-types count)
               (-> right ::parameter-types count))
      ; We can't unify these incompatible fns.
      (do
        (println "error: incompatible fn arities" left "and" right)
        nil)
      (let [substitutions (unify (::return-type left) (::return-type right) substitutions)]
        (reduce (fn [acc [left-param right-param]]
                  (unify-variable left-param right-param acc))
                substitutions
                (map vector (::parameter-types left) (::parameter-types right)))))

    ; Shouldn't happen.
    :else
    (do
      (println "error: unable to unify" left "and" right)
      nil)))

(defn unify-equations [equations]
  (let [substitutions {}]
    (reduce (fn [acc [left right]]
              (if-some [new-acc (unify left right acc)]
                new-acc
                (reduced nil)))
            substitutions
            equations)))

(defn apply-substitutions [typ substitutions]
  (cond
    (nil? substitutions)
    nil

    (empty? substitutions)
    typ

    (not (contains? #{::unknown ::function} (::type-kind typ)))
    typ

    (= ::unknown (::type-kind typ))
    (if-some [sub (get substitutions (::name typ))]
      (apply-substitutions sub substitutions)
      typ)

    (= ::function (::type-kind typ))
    (-> typ
        (update ::return-type apply-substitutions substitutions)
        (update ::parameter-types (fn [param-types]
                                    (map #(apply-substitutions % substitutions) param-types))))

    :else
    nil))

(defn render-type
  [typ]
  (case (::type-kind typ)
    nil
    "unsolvable"

    (::single ::unknown)
    (::name typ)

    ::function
    (let [params (map render-type (::parameter-types typ))
          ret (render-type (::return-type typ))]
      (str "((" (clojure.string/join ", " params) ") -> " ret ")"))))

(comment
  (render-type {::type-kind ::function
                ::parameter-types [{::type-kind ::single
                                    ::name "string"}
                                   {::type-kind ::single
                                    ::name "boolean"}
                                   {::type-kind ::function
                                    ::parameter-types [{::type-kind ::unknown
                                                        ::name "a"}]
                                    ::return-type {::type-kind ::unknown
                                                   ::name "a"}}]
                ::return-type {::type-kind ::single
                               ::name "string"}}))
