# ascolais/sandestin

An effect dispatch library for Clojure with schema-driven discoverability.

Sandestin provides a structured way to dispatch side effects while maintaining excellent introspection capabilities. It's designed to work seamlessly with LLM-assisted workflows and REPL-driven development.

## Features

- **Effect dispatch** - Dispatch vector-based effects with composable registries
- **Actions** - Pure functions that expand into effect sequences
- **Placeholders** - Late-bound value resolution from dispatch context
- **Interceptors** - Lifecycle hooks for instrumentation and control flow
- **Schema-driven** - Malli schemas for all registered items
- **Discoverability** - Built-in functions to describe, sample, search, and inspect

## Installation

Add to your `deps.edn`:

```clojure
{:deps
 {io.github.brianium/sandestin {:git/tag "v0.1.0" :git/sha "..."}}}
```

## Quick Start

```clojure
(ns myapp.core
  (:require [ascolais.sandestin :as s]))

;; Define a registry with effects
(def my-registry
  {::s/effects
   {:myapp/log
    {::s/description "Log a message"
     ::s/schema [:tuple [:= :myapp/log] :string]
     ::s/handler (fn [_ctx _system msg]
                   (println msg)
                   :logged)}}})

;; Create a dispatch function
(def dispatch (s/create-dispatch [my-registry]))

;; Dispatch effects
(dispatch {} {} [[:myapp/log "Hello, Sandestin!"]])
;; => {:results [{:effect [:myapp/log "Hello, Sandestin!"], :res :logged}]
;;     :errors []}
```

## Core Concepts

### Effects

Effects are side-effecting operations. Each effect has a handler that receives:
- `ctx` - Context map with `:dispatch`, `:dispatch-data`, `:system`
- `system` - The live system map (database connections, config, etc.)
- `& args` - Additional arguments from the effect vector

```clojure
{::s/effects
 {:db/execute
  {::s/description "Execute a SQL query"
   ::s/schema [:tuple [:= :db/execute] :string [:* :any]]
   ::s/system-keys [:datasource]
   ::s/handler (fn [{:keys [dispatch]} system sql & params]
                 (let [result (jdbc/execute! (:datasource system)
                                             (into [sql] params))]
                   ;; Optionally dispatch continuation effects
                   (dispatch {:result result} [[::log "Query complete"]])
                   result))}}}
```

### Actions

Actions are pure functions that transform state into effect vectors. They receive immutable state (extracted via `::system->state`) and return effects.

```clojure
{::s/actions
 {:myapp/greet-user
  {::s/description "Greet a user and log the event"
   ::s/schema [:tuple [:= :myapp/greet-user] :string]
   ::s/handler (fn [state username]
                 [[:myapp/log (str "Hello, " username "!")]
                  [:myapp/save-greeting {:user username :at (System/currentTimeMillis)}]])}}

 ::s/system->state
 (fn [system] (:app-state system))}
```

### Placeholders

Placeholders resolve values from dispatch-data at dispatch time. They enable late binding of values, particularly useful for async continuations.

```clojure
{::s/placeholders
 {:myapp/current-user
  {::s/description "Get current user from dispatch context"
   ::s/schema :map
   ::s/handler (fn [dispatch-data]
                 (:current-user dispatch-data))}}

 ::s/effects
 {:myapp/greet
  {::s/handler (fn [_ctx _sys user]
                 (str "Hello, " (:name user) "!"))}}}

;; Usage with placeholder
(dispatch {} {:current-user {:name "Alice"}}
          [[:myapp/greet [:myapp/current-user]]])
```

### Interceptors

Interceptors provide lifecycle hooks around dispatch, action expansion, and effect execution.

```clojure
(def logging-interceptor
  {:id ::logging
   :before-dispatch (fn [ctx] (tap> {:event :dispatch-start}) ctx)
   :after-dispatch (fn [ctx] (tap> {:event :dispatch-end :errors (:errors ctx)}) ctx)
   :before-effect (fn [ctx] (tap> {:event :effect :effect (:effect ctx)}) ctx)})

{::s/interceptors [logging-interceptor]}
```

Built-in interceptors:
- `ascolais.sandestin.interceptors/fail-fast` - Stop on first error

## Discoverability

Sandestin is designed for LLM-assisted development. Use these functions to explore registered items:

### describe

```clojure
;; All items
(s/describe dispatch)

;; By type
(s/describe dispatch :effects)
(s/describe dispatch :actions)
(s/describe dispatch :placeholders)

;; Specific item
(s/describe dispatch :db/execute)
;; => {:ascolais.sandestin/key :db/execute
;;     :ascolais.sandestin/type :effect
;;     :ascolais.sandestin/description "Execute a SQL query"
;;     :ascolais.sandestin/schema [:tuple ...]
;;     :ascolais.sandestin/system-keys [:datasource]}
```

### sample

Generate sample data using Malli generators:

```clojure
(s/sample dispatch :db/execute)
;; => [:db/execute "generated-string" 42]

(s/sample dispatch :db/execute 3)
;; => ([:db/execute ...] [:db/execute ...] [:db/execute ...])
```

### grep

Search by pattern:

```clojure
(s/grep dispatch "database")
;; => ({:ascolais.sandestin/key :db/execute ...})

(s/grep dispatch #"log|save")
;; => items matching the regex
```

### schemas

Get all schemas as a map:

```clojure
(s/schemas dispatch)
;; => {:db/execute [:tuple ...], :myapp/log [:tuple ...], ...}
```

### system-schema

Get merged system requirements:

```clojure
(s/system-schema dispatch)
;; => {:datasource [...schema...], :config [...schema...]}
```

## Registry Structure

A registry is a map with these keys (all namespaced under `ascolais.sandestin`):

```clojure
{::s/effects      {qualified-keyword -> EffectRegistration}
 ::s/actions      {qualified-keyword -> ActionRegistration}
 ::s/placeholders {qualified-keyword -> PlaceholderRegistration}
 ::s/interceptors [Interceptor ...]
 ::s/system-schema {keyword -> MalliSchema}
 ::s/system->state (fn [system] state)}
```

### Registration Maps

```clojure
;; Effect
{::s/description "Human-readable description"
 ::s/schema [:tuple [:= :effect/key] ...args...]
 ::s/handler (fn [ctx system & args] result)
 ::s/system-keys [:datasource :config]}  ; optional

;; Action
{::s/description "..."
 ::s/schema [:tuple [:= :action/key] ...args...]
 ::s/handler (fn [state & args] [[effects...]])}

;; Placeholder
{::s/description "..."
 ::s/schema MalliSchema  ; schema for the resolved value
 ::s/handler (fn [dispatch-data & args] value)}

;; Interceptor
{:id :qualified/keyword
 :before-dispatch (fn [ctx] ctx)
 :after-dispatch (fn [ctx] ctx)
 :before-action (fn [ctx] ctx)
 :after-action (fn [ctx] ctx)
 :before-effect (fn [ctx] ctx)
 :after-effect (fn [ctx] ctx)}
```

## Composing Registries

Registries can be composed from multiple sources:

```clojure
(def dispatch
  (s/create-dispatch
    [[db/registry {:dbtype "postgresql"}]  ; vector [fn & args]
     auth/registry                          ; zero-arity fn
     {:myapp/effects {...}}]))              ; plain map
```

Merge rules:
- Effects, actions, placeholders: later wins on conflict (with tap> warning)
- Interceptors: concatenated in order
- system-schema: merged (later wins per key)
- system->state: last wins

## Dispatch Flow

1. Run before-dispatch interceptors
2. Interpolate placeholders in input
3. Expand actions to effects (with before/after-action interceptors)
4. Execute effects (with before/after-effect interceptors)
5. Run after-dispatch interceptors
6. Return `{:results [...] :errors [...]}`

## Development

### Start the REPL

```bash
clj -M:dev
```

### Development Workflow

```clojure
(dev)      ; Switch to dev namespace
(start)    ; Start system (opens Portal)
(reload)   ; Reload changed namespaces
(restart)  ; Full restart
```

### Testing

```bash
clj -X:test
```

## License

Copyright 2025 Brian Scaturro

Distributed under the Eclipse Public License version 1.0.
