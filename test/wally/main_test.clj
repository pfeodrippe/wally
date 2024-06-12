(ns wally.main-test
  (:require [clojure.test :refer [are deftest]]
            [garden.selectors :as s]
            [wally.main :as w]
            [wally.selectors :as ws]))

(deftest query->selector
  (are [q expected] (= expected (w/query->selector q))
    (s/attr "href") "[href]"
    :#foo "#foo"
    :.bar ".bar"
    "baz" "baz"
    [:#foo :.bar "baz"] "#foo >> .bar >> baz"
    (list "x" "y") "x >> y"
    [[:.foo (ws/text "bar")]] ".foo:text(\"bar\")"
    (list (list :.x :.y)) ".x.y"))
