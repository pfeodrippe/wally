(ns build
  (:require
   [clojure.tools.build.api :as b]
   #_[nextjournal.clerk.render.hashing :refer [lookup-url]]
   #_[org.corfield.build :as bb]))

(def lib 'myname/mylib)
;; if you want a version of MAJOR.MINOR.COMMITS:
(def version (format "0.0.%s" (b/git-count-revs nil)))
