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
   (clojure.lang IFn)
   (com.microsoft.playwright BrowserType BrowserType$LaunchOptions
                             BrowserType$LaunchPersistentContextOptions
                             Download Locator$ClickOptions Locator$DblclickOptions
                             Locator$WaitForOptions Page Page$RouteOptions
                             Page$WaitForSelectorOptions Playwright Response Route
                             TimeoutError)
   (com.microsoft.playwright.options WaitForSelectorState SelectOption)
   (garden.selectors CSSSelector)
   (java.io File)
   (java.nio.file Paths)
   (java.util.function Predicate)
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

(defn- launch-persistent
  ^Page [^BrowserType browser-type headless]
  (io/make-parents user-data-dir)
  (-> browser-type
      (.launchPersistentContext
       ;; We start chromium with persistent data
       ;; so we can login to Google (e.g. for QA develop
       ;; admin) only once during days.
       (Paths/get (java.net.URI.
                   (str "file://"
                        (.getAbsolutePath user-data-dir))))
       (-> (BrowserType$LaunchPersistentContextOptions.)
           (.setHeadless headless)
           (.setSlowMo 50)))
      .pages
      (first)))

(defn- launch-non-persistent
  ^Page [^BrowserType browser-type headless]
  (-> browser-type
      (.launch
       (-> (BrowserType$LaunchOptions.)
           (.setHeadless headless)
           (.setSlowMo 50)))
      .newPage))

(defonce ^:private page->playwright (atom {}))

(defn make-page
  (^Page
   []
   (make-page {}))
  (^Page
   [{:keys [headless persistent]
     :or {headless false
          persistent true}}]
   (delay
     (let [pw (Playwright/create)
           page ((if persistent launch-persistent launch-non-persistent)
                 (.chromium pw)
                 headless)]
       (doto page
         (.setDefaultTimeout 10000)
         (#(swap! page->playwright assoc % pw))
         (.onClose #(swap! page->playwright dissoc %)))))))

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

(defmacro with-page-open
  [page & body]
  `(with-page ~page
     (try
       ~@body
       (finally
         (let [page# (get-page)
               context# (.context page#)
               playwright# (get @#'page->playwright page#)]
           ;; If this is the last page, then instead of closing just
           ;; the page, close the entire browser, to not leave
           ;; abandoned browser processes after test runs.
           ;; Also, close the Playwright process itself.
           (if (= [page#] (.pages context#))
             (do (-> context# .browser .close)
                 (some-> ^Playwright playwright# .close))
             (.close page#)))))))

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

(defrecord TestId [testid])

(defn query->selector
  [q]
  (cond
    (instance? CSSSelector q)
    (s/css-selector q)

    (instance? TestId q)
    (query->selector (s/attr= :data-testid (query->selector (.testid q))))

    (and (sequential? q)
         (sequential? (first q))
         (= (count q) 1))
    ;; Nested vector/list represents a logical conjunction (and).
    (->> (map query->selector (first q))
         (str/join))

    (sequential? q)
    ;; Chain comands if query is a list or a vector.
    (->> (mapv query->selector q)
         (interpose ">>")
         (str/join " "))

    (keyword? q)
    (str (symbol q))

    :else
    q))

(defn go-back
  []
  (.. (get-page) goBack))

(declare -query)

(defn query
  ^SeqableLocator
  [q]
  (if (instance? SeqableLocator q)
    q
    (let [locator (cond
                    (instance? com.microsoft.playwright.Locator q)
                    q

                    ;; Subquery - a vector/list starting with a (sequable)locator searches in its subtree(s).
                    (and (sequential? q)
                         (or (instance? SeqableLocator (first q))
                             (instance? com.microsoft.playwright.Locator (first q))))
                    (.. (-query (first q)) (locator (query->selector (rest q))))

                    :else
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
  [q & [^Locator$ClickOptions opts]]
  (.. (-query q) (click opts)))

(defcommand dblclick
  [q & [^Locator$DblclickOptions opts]]
  (.. (-query q) (dblclick opts)))

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

(defn attr
  "Returns an attribute value."
  [q attr-name]
  (.getAttribute (-query q) (name attr-name)))

(defn value
  "Returns a form field value."
  [q]
  (.inputValue (-query q)))

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

(defn wait-for-not-visible
  "Waits for element to be either not present in DOM or hidden.
  `timeout` is in milliseconds, defaults to the page timeout.

  See https://playwright.dev/java/docs/api/class-page#page-wait-for-selector
  for more details.

  On success, returns `true`. Otherwise, throws an error."
  ([q]
   (wait-for-not-visible q {}))
  ([q {:keys [timeout]}]
   (wait-for q {:state :hidden
                :timeout timeout})
   true))

(defn wait-for-response
  "Blocks and returns the response matching `url-pattern`. `triggering-action` is a function performing
  the action triggering the request. This action argument is useful in avoiding race conditions, because
  Playwright Java is single-threaded and not thread-safe."
  ([url-pattern] (wait-for-response (fn []) url-pattern))
  ([^IFn triggering-action url-pattern]
   (let [*p (promise)]
     (.waitForResponse
      (get-page)
      (reify Predicate
        (test [_ response]
          (let [^Response response response]
            (boolean
             (when (re-matches url-pattern (.url response))
               (deliver *p
                        {:status (.status response)
                         :body (parse-json-string (slurp (.body response)))})
               true)))))
      triggering-action)
     @*p)))

(defn wait-for-url
  "Waits for the main frame to navigate to the given `url`
  (a string, a regex or a fn).

  On success, returns `true`. Otherwise, throws an error."
  [url]
  (.waitForURL (get-page) url)
  true)

(defmacro with-slow-network
  "Simulate slow network by delaying sending network request(s)
  matching the `url`. The `url` can be a string or a regex.
  In addition, wait for a matching request to be sent at least
  once, making sure that the simulation setup works."
  [{:keys [url delay request-count] :or {delay 100 request-count 1}} & body]
  `(.waitForRequest
    (get-page)
    ~url
    (fn trigger-slow-network-request# []
      ;; Setup a delay.
      (.route
       (get-page)
       ~url
       (fn [^Route route#]
         (Thread/sleep ~delay)
         (.resume route#))
       (doto (Page$RouteOptions.) (.setTimes ~request-count)))
      ;; Trigger the request(s).
      ~@body)))

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
  "Press keyboard. If multiple args passed, keys pressed in succession.

  See https://playwright.dev/docs/api/class-keyboard.

  E.g. `(keyboard-press \"Enter\")`"
  [& keys]
  (run! (fn [key] (.. (get-page) keyboard (press key))) keys))

(defn get-by-label
  "Locate a form control by associated label's text."
  [label]
  (.getByLabel (get-page) label))

(defmacro maybe
  "Returns `nil` in case that Playwright times out when waiting."
  [& body]
  `(try
     ~@body
     (catch TimeoutError _#)))

(defn eval-js
  "Runs a JavaScript function in the context of the web page.
  An optional `arg` (e.g. a primitive, vector, map) can be passed to the function.

  See: https://playwright.dev/java/docs/evaluating"
  ([^String js] (eval-js js nil))
  ([^String js arg]
   (.evaluate (get-page) js arg)))

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

  ;; Subqueries
  (= 4
     (count (w/query ["#jar-info-bar" "li"]))
     (count (w/query [(w/query "#jar-info-bar") "li"]))
     (count (w/query [(first (w/query "#jar-info-bar")) "li"])))

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
