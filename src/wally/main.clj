(ns wally.main
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [garden.selectors :as s]
   [jsonista.core :as json]
   [wally.selectors :as ws])
  (:import
   (com.microsoft.playwright BrowserType$LaunchPersistentContextOptions
                             Download Locator$WaitForOptions
                             Page Page$WaitForSelectorOptions
                             Playwright Response)
   (com.microsoft.playwright.impl LocatorImpl)
   (com.microsoft.playwright.options WaitForSelectorState SelectOption)
   (garden.selectors CSSSelector)
   (java.io File)
   (java.nio.file Paths)
   (com.microsoft.playwright.assertions PlaywrightAssertions)))

(def ^:private object-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn parse-json-string
  [s]
  (try
    (json/read-value s object-mapper)
    (catch Exception _ s)))

(def ^File user-data-dir
  "Folder for the browser."
  (io/file ".wally/webdriver/data"))

(defn make-page
  (^Page
   []
   (make-page {}))
  (^Page
   [{:keys [headless]
     :or {headless false}}]
   (delay
     (let [pw (Playwright/create)]
       (io/make-parents user-data-dir)
       (-> (.. pw chromium (launchPersistentContext
                            ;; We start chromium with persistent data
                            ;; so we can login to Google (e.g. for QA develop
                            ;; admin) only once during days.
                            (Paths/get (java.net.URI.
                                        (str "file://"
                                             (.getAbsolutePath user-data-dir))))
                            (-> (BrowserType$LaunchPersistentContextOptions.)
                                (.setHeadless headless)
                                (.setSlowMo 50))))
           .pages
           ^Page (first)
           (doto (.setDefaultTimeout 10000)))))))

(defonce ^:dynamic ^Page *page*
  (make-page))

(def ^:dynamic *opts*
  {::opt.command-delay 0})

(defn get-page
  ^Page
  []
  (if (delay? *page*)
    @*page*
    *page*))

(defmacro with-page
  [page & body]
  `(binding [*page* ~page]
     ~@body))

(defmacro with-opts
  [opts & body]
  `(binding [*opts* ~opts]
     ~@body))

(defprotocol Locator
  (-locator [_]))

;; `SeqableLocator` is a wrapper over a Playwright locator
;; so we can iterate over a query.
;; E.g. (run! w/click (take 3 (SeqableLocator. locator))) would
;; make Playwright click on the first 3 elements (if existent).
(deftype SeqableLocator [^com.microsoft.playwright.Locator locator]
  clojure.lang.ISeq
  (seq [_this]
    ;; Wait for one element or throw an exception otherwise.
    (.waitFor
     (.first locator)
     (.setState (Locator$WaitForOptions.)
                (WaitForSelectorState/valueOf "ATTACHED")))
    ;; And then return seq.
    (seq (map #(.nth locator %)
              (range (.count locator)))))
  (next [this]
    (next (seq this)))
  (first [this]
    (first (seq this)))
  (count [this]
    (count (seq this)))
  (cons [this el]
    (cons el (seq this)))

  clojure.lang.Indexed
  (nth [this i]
    (nth (seq this) i))
  (nth [this i not-found]
    (nth (seq this) i not-found))

  Locator
  (-locator [_] locator)

  Object
  (toString [this]
    (str "#SeqableLocator[count=" (count (seq this)) "," (str locator) "]")))

(defmethod print-method SeqableLocator
  [o ^java.io.Writer w]
  (.write w (str o)))

(defmethod pp/simple-dispatch SeqableLocator
  [o]
  (pr (str o)))

(defn query->selector
  [q]
  (cond
    (instance? CSSSelector q)
    (s/css-selector q)

    (sequential? q)
    ;; Chain comands if query is a list or a vector.
    (->> (mapv query->selector q)
         (interpose ">>")
         (str/join " "))

    (or (str/starts-with? (name q) "#")
        (str/starts-with? (name q) "."))
    (name q)

    (keyword? q)
    (str "." (name q))

    :else
    q))

(defn go-back
  []
  (.. (get-page) goBack))

(defn query
  ^SeqableLocator
  [q]
  (if (instance? SeqableLocator q)
    q
    (let [locator (if (= (type q) LocatorImpl)
                    q
                    (.. (get-page) (locator (query->selector q))))]
      (SeqableLocator. locator))))

(defn -query
  "Like `query`, but returns a locator instead of a `SeqableLocator`.
  You can use it when you want to interact directly with a Playwright locator,
  check https://playwright.dev/java/docs/api/class-locator."
  ^com.microsoft.playwright.Locator
  [q]
  (let [q (query q)]
    (if (instance? SeqableLocator q)
      (-locator q)
      q)))

(defn find-one-by-text
  [q text]
  (->> (query q)
       (filter #(= (.allTextContents ^com.microsoft.playwright.Locator %) [text]))
       first))

(defn navigate
  [url]
  (.. (get-page) (navigate url)))

;; Commands.
(defmacro defcommand
  "Like `defn`, but adds some extra properties to the function relevant
  to Wally commands."
  {:arglists '([name doc-string? [params*] body])}
  [name & args]
  (let [[doc-string params body] (if (string? (first args))
                                   [(first args) (second args) (rest (rest args))]
                                   [nil (first args) (rest args)])]
    `(defn ~name
       ~(or doc-string {})
       ~params
       #_( {:op (quote ~name) :params ~params})
       (try
         ~@body
         (finally
           (when (pos? (::opt.command-delay *opts*))
             (Thread/sleep (::opt.command-delay *opts*))))))))

(defcommand click
  [q]
  (.. (-query q) click))

(defcommand fill
  [q value]
  (.. (-query q) (fill value)))

(defcommand select
  "Possible values for options are
  - string
  - {:index n}
  - {:label \"...\"}"
  [q option]
  (let [^String option (when (string? option)
                         option)
        ^SelectOption select-option (when-not option
                                      (cond
                                        (:index option) (doto (SelectOption.)
                                                          (.setIndex (:index option)))
                                        (:label option) (doto (SelectOption.)
                                                          (.setIndex (:label option)))))]
    (if option
      (.. (-query q) (selectOption option))
      (.. (-query q) (selectOption select-option)))))

;; Helper functions.
(defn fill-many
  [coll]
  (mapv #(fill (first %) (last %)) coll))

(defn wait
  [q]
  (.. (-query q) waitFor))

(defn refresh
  []
  (.reload (get-page)))

(defn download-file
  [q]
  (let [[suggested-filename
         path]
        (-> (.waitForDownload (get-page) #(click q))
            ((juxt #(.suggestedFilename ^Download %)
                   #(.path ^Download %))))]
    {:suggested-filename suggested-filename
     :path (str path)}))

(defn upload-file
  [q file-path]
  (.. (-query q)
      (setInputFiles (Paths/get (java.net.URI. (str "file://" file-path))))))

(defn wait-for
  "`state` may be :hidden, :visible, :attached or :detached, defaults to `:visible`.
  `timeout` is in milliseconds, defaults to the page timeout.

  See https://playwright.dev/java/docs/api/class-page#page-wait-for-selector for more
  context."
  ([q]
   (wait-for q {}))
  ([q {:keys [state timeout]}]
   (.waitForSelector
    (get-page)
    (query->selector q)
    (cond-> (Page$WaitForSelectorOptions.)
      state (.setState
             (WaitForSelectorState/valueOf
              (csk/->SCREAMING_SNAKE_CASE_STRING state)))
      timeout (.setTimeout timeout)))))

(defn wait-for-response
  "Returns response."
  [match]
  ;; `Page.waitForResponse` receives a predicate and a runnable,
  ;; we ignore the runnable and call the handler from the predicate
  ;; itself.
  (let [*p (promise)]
    (.waitForResponse
     (get-page)
     (reify java.util.function.Predicate
       (test [_ response]
         (let [^Response response response]
           (when (re-matches match (.url response))
             (deliver *p
                      {:status (.status response)
                       :body (parse-json-string (slurp (.body response)))})
             true))))
     (fn []))
    @*p))

(defn count*
  [^com.microsoft.playwright.Locator locator]
  (.count locator))

(defn all-text-contents
  "Find all text contents for a query. It returns a vector as a query
  may contain multiple matches."
  [q]
  (.allTextContents (-query q)))

(defn text-content
  "Find one text content for a query. It's like `all-text-contents`, but
  it returns only one match and throws an exceptions if there is more than
  one element matching.

  It returns `nil` if no match is found."
  [q]
  (let [contents (all-text-contents q)]
    (if (> (count contents) 1)
      (throw (ex-info (str `text-content " - Query matches more than 1 element.")
                      {:q q}))
      (first contents))))

(defn visible?
  [q]
  (.isVisible (-query q)))

(defn in-viewport?
  "Check that element is in viewport.

  See https://playwright.dev/java/docs/api/class-locatorassertions#locator-assertions-to-be-in-viewport."
  [q]
  ;; `.isInViewport` returns `nil` if successful or
  ;; explodes if unsuccessful.
  (-> (-query q)
      PlaywrightAssertions/assertThat
      .isInViewport)
  true)

(defn url
  []
  (.url (get-page)))

(defn keyboard-press
  "Press keyboard.

  See https://playwright.dev/docs/api/class-keyboard.

  E.g. `(keyboard-press \"Enter\")`"
  [key]
  (.. (get-page) keyboard (press key)))

(comment

  (require '[wally.selectors :as ws])
  (require '[wally.main :as w])

  ;; Copy jsonista deps.edn dep.
  (do
    (w/navigate "https://clojars.org/metosin/jsonista")
    (w/click [(ws/text "Copy") (ws/nth= "1")]))

  ;; Check number of downloads for reitit.
  (do
    (w/fill :#search "reitit")
    (w/keyboard-press "Enter")
    (w/click (s/a (s/attr= :href "/metosin/reitit")))
    (.textContent (w/-query (ws/text "Downloads"))))

  ())

;; TODO (main):
;; - [x] We need to make SeqableLocator to always work on top of the selector so
;;       we don't have issues for when the element doesn't exist
;;   - [x]  Or we can make a SeqableSelector instead
;; - [x] Create custom executor in Clerk
;;   - [x] Pipeline viewer
;;   - [ ] Control delay for each step (or a group of steps)
;; - [ ] Simple Clerk tracer
;; - [ ] Explain and act on each step slowly (if the user desires so)
;; - [ ] Create protocol for making queries extensible
;;   - [ ] Users could make a query out of any object
