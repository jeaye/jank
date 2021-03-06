(ns jank.parse
  (:require [instaparse.core :as insta]
            [jank.parse.transform :as transform])
  (:use jank.assert
        jank.debug.log))

(def prelude (slurp (clojure.java.io/resource "prelude.jank")))

(insta/defparser whitespace-or-comments-parser
  (clojure.java.io/resource "whitespace-grammar"))

(insta/defparser parser
  (clojure.java.io/resource "grammar")
  :auto-whitespace whitespace-or-comments-parser)

(defn parse
  "Runs the provided resource file through instaparse. Returns
   then generated syntax tree."
  ([resource] (parse prelude resource))
  ([pre resource]
   ;(pprint "parsing" (str pre resource))
   (let [parsed (parser (str pre resource))
         error (pr-str (insta/get-failure parsed))
         _ (parse-assert (not (insta/failure? parsed))
                         "invalid syntax\n" error)
         transformed (insta/transform
                       {:integer (partial transform/read-single :integer)
                        :real (partial transform/read-single :real)
                        :boolean (partial transform/read-single :boolean)
                        ; TODO: Have this produce a type in the grammar
                        :keyword (partial transform/single :keyword)
                        :string (partial transform/single :string)
                        :type (partial transform/single :type)
                        :condition (partial transform/single :condition)
                        :then (partial transform/single :then)
                        :else (partial transform/single :else)
                        :identifier transform/identifier
                        :specialization-list transform/specialization-list
                        :generic-specialization-list transform/generic-specialization-list
                        :type-declaration (partial transform/declaration :type)
                        :binding-declaration (partial transform/declaration :binding)
                        :binding-definition transform/binding-definition
                        :struct-definition transform/struct-definition
                        :struct-member transform/struct-member
                        :new-expression transform/new-expression
                        :macro-function-call transform/function-call
                        :macro-definition transform/macro-definition
                        :lambda-definition transform/lambda-definition
                        :generic-lambda-definition transform/lambda-definition
                        :argument-list transform/argument-list
                        :macro-argument-list transform/macro-argument-list
                        :syntax-definition transform/syntax-definition
                        :syntax-list transform/syntax-list
                        :syntax-item transform/syntax-item
                        :syntax-escaped-item (partial transform/single :syntax-escaped-item)
                        :escaped-item (partial transform/single :escaped-item)
                        :return-list transform/return-list
                        :if-expression transform/if-expression}
                       parsed)]
     ;(pprint "parsed" parsed)
     ;(pprint "transformed" transformed)
     transformed)))

(defn parses [source & args]
  (apply insta/parses parser source args))
