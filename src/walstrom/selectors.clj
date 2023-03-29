(ns walstrom.selectors
  (:require
   [garden.selectors :as sel]))

(sel/defpseudoclass text
  [v]
  (sel/selector (pr-str v)))

(sel/defselector nth=)
