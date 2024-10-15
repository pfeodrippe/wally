(ns wally.repl
  "Utilities for interacting with a page from the REPL while in the middle of a test (or other script).

  E.g. Having `(wally.repl/pause)` inside the `book-of-clerk` test:
  `(do (wally.repl/resume) (future (clojure.test/run-test book-of-clerk)))` ; Restart the test.
  `(wally.repl/with-page (w/click (s/a (s/attr= :href \"#recursion\"))))`   ; Eval in context of paused test."
  (:require [wally.main :as w]))

(defonce ^:private halt (promise))

(defonce ^:private page (atom nil))

(defn pause
  "Put this in a test where you want to pause to use the REPL.
  Make sure to run the test in a separate thread to not block the REPL."
  []
  (reset! page w/*page*)
  @halt)

(defmacro with-page
  "Wrap REPL commands in this macro to retain page context
  if a paused test is using `w/with-page`."
  [& body]
  `(w/with-page @@#'page
     ~@body))

(defn resume
  "Run this in the REPL to resume a test and prepare for next run."
  []
  (deliver halt true)
  (alter-var-root #'halt (constantly (promise))))
