[![Clojars Project](https://img.shields.io/clojars/v/io.github.pfeodrippe/walstrom.svg)](https://clojars.org/io.github.pfeodrippe/walstrom)

# Walstrom

A Clojure [Playwright](https://playwright.dev/) wrapper.

Chad Walstrom is a very nice person, so I have created this library for him,
also because I needed a library where I could use the `w` alias, but mostly
because I am very fond of this good man =D

Very early alpha version, expect breaking changes!

## Usage

```clojure
;; Here you have the main Walstrom namespace.
(require '[walstrom.main :as w])

;; Here you have some custom garden selectors.
(require '[walstrom.selectors :as ws])

;; Copy jsonista deps.edn dep.
(do
  ;; When some command is run for the first time, Playwright
  ;; will kick in and open a browser.
  (w/navigate "https://clojars.org/metosin/jsonista")
  (w/click [(ws/text "Copy") (ws/nth= "1")]))

;; Check number of downloads for reitit.
(do
  (w/fill :#search "reitit")
  (w/keyboard-press "Enter")
  (w/click (s/a (s/attr= :href "/metosin/reitit")))
  (.textContent (w/-query (ws/text "Downloads"))))

;; Get the Playwright page object.
;; https://playwright.dev/docs/api/class-page.
(w/get-page)
```
