[![Clojars Project](https://img.shields.io/clojars/v/io.github.pfeodrippe/wally.svg)](https://clojars.org/io.github.pfeodrippe/wally)

# Wally

A Clojure [Playwright](https://playwright.dev/) wrapper.

Wally is a very nice person I work with, so I have created this library for him,
also because I needed a library where I could use the `w` alias, but mostly
because I am very fond of this good man =D

Very early alpha version, expect breaking changes!


Playwright is in the same category as the etaoin webdriver (parts of the API were heavily influenced by it), but leaning towards Cypress as there is huge support for testing, but, different from Cypress and like etaoin, PW just works in the REPL.

## Example

See an awesome example project made by https://github.com/PEZ (thanks!), https://github.com/PEZ/wally-example.

## Usage

```clojure
;; Here you have the main Wally namespace.
(require '[wally.main :as w])

;; Here you have some custom garden selectors + the usual ones.
(require '[wally.selectors :as ws])
(require '[garden.selectors :as s])

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

## Video

NOTE: In the video below, replace `walstrom` (old project name) with `wally` (new project name) for
the requires.

https://user-images.githubusercontent.com/2124834/228414407-025b4014-b479-4aa3-bdab-761a8df22791.mp4
