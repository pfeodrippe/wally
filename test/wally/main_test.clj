(ns wally.main-test
  (:require [clojure.test :refer [are deftest]]
            [garden.selectors :as s]
            [wally.main :as w]))

(deftest query->selector
  (are [q expected] (= expected (w/query->selector q))
    (s/attr "href") "[href]"
    :#foo "#foo"
    :.bar ".bar"
    "baz" "baz"
    [:#foo :.bar "baz"] "#foo >> .bar >> baz"
    (list "x" "y") "x >> y"))
