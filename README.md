# dawn

An interpreter and evaluator for the dawn trading strategy language.

The purpose of dawn is to declaratively state what orders should exist based on a state machine and external signals. The language syntax is based on TOML, adding an expression language on top to allow for dynamically evaluated values. The runtime expects a particular TOML structure to be able to execute. The dawn language is described in [STRATEGY.md](https://github.com/danielytics/dawn-script/blob/main/STRATEGY.md)
Dawn is a [Synchronous programming language](https://en.wikipedia.org/wiki/Synchronous_programming_language), specifically an event-driven transformational system, that is designed to be run inside a database transaction: all inputs (current state + input data/events) are gathered ahead of time and the script is then atomically evaluated as a pure function of this input, which produces as output the new state, logs, and the orders that should exist.
The actual bot stored the state and logs in postgres and diffed the orders with the actual state of orders in the users account in order to generate an "order plan": a series of add, modify or delete actions to be performed against the exchange to bring it in line with the desired orders.

*NOTE:* Clojurescript support was never completed as parser.cljc never added cljs support for TOML parsing.

## Usage

Dawn is meant to be used as a library, either from Cljoure or Clojurescript.

Leiningen:
```
[dawn-script "0.1.2-SNAPSHO"]
```

Require the library:

```clj
[dawn.core :as dawn]
```

For example:
```clj
(ns my.example
  (require [dawn.core :as dawn]))
```

To load a TOML file (Clojure only):
```clj
(def strategy (dawn/load-file "my-strategy.toml"))
```

Alternatively, to load TOML source from a string (both Clojure and Clojurescript):

```clj
(def source """
[states]
    initial = "init"
    [[states.state]]
        id = "init"
""")
(def strategy (dawn/load-string source))
(when-let [errors (:errors strategy)]
  (println errors))
```

In either case, the strategy will contain an `:errors` field, which will be `nil` if the source parsed without problem, or a vector of errors if any errors occurred. If it was `nil`, then the strategy is ready to be executed.

To execute a strategy:

```clj
; External global inputs: signals, price lines, kline/candlestick data etc
(def inputs {:input-name 0})
; Configuration data, defined in strategy, set by user per strategy instance, set once per strategy
(def configs {:config-name 0})
; Per instance data: user-data, internal state etc -- created and set interanlly by strategy, should be persisted between calls
; If passing in {}, a new instance is created.
(def data {})

; Execute an instance of the strategy
(def results (dawn/execute startegy {:inputs inputs
                                     :config config
                                     :data data}))

(if-let [errors (:errors results)]
  (println errors)
  (do
    ; This data should be persisted for the next call
    (println (:data results))
    ; These actions should be performed (order placements/edits/cancellations, logging, etc)
    (println (:actions results))))
```

If the execution was successful, it will not contain an `:errors` field (additionally, the `:status` field will be set to `:success`). If the execution was not successful, then an `:errors` field will be present (additionally, the `:status` field will be set to `:failure`).

If the execution was successfel, then the results will contain `:data` and `:actions` fields. The `:data` should be persisted and used as the `:data` input the next time the strategy is to be executed. Its value should be treated as opague and internal. The `:actions` key contains a vector of actions describing actions which should be performed on behalf of the strategy. Each action is a map with an `:action/type` field defininig what type of action it is, as well as zero or more type-specific fields.

If the execution failed, then the results will contain an `:errors` field, which is a vector of error maps, describing the errors encountered during execution of the strategy.


# Architecture

## Project Structure

src/dawn/

 * core.cljc -> public API
 * parser.cljc -> load TOML file and parse text based on grammar, output is state structure with embedded formula AST's
 * evaluator.cljc -> a pure function of context map and formula AST which outputs result of evaluating formula 
 * builtins.cljc -> library of built in (implemented in Clojure) functions that can be called during evaluation
 * runtime.cljs -> takes a state definition (parsed and processed TOML file), configuration, stored state and external inputs and produces new stored state and list of external actions to perform

 resources/

  * grammar.ebnf -> EBNF file defining the grammar rules for parsing formulas
