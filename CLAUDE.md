# ascolais/sandestin

## Project Overview

This is a Clojure project using deps.edn for dependency management.

## Technology Stack

- **Clojure** with deps.edn
- **clj-reload** for namespace reloading during development
- **Portal** for data inspection (tap> integration)
- **Cognitect test-runner** for running tests

## Development Setup

### Starting the REPL

```bash
clj -M:dev
```

This starts a REPL with development dependencies loaded.

### Development Workflow

1. Start REPL with `clj -M:dev`
2. Load dev namespace: `(dev)`
3. Start the system: `(start)`
4. Make changes to source files
5. Reload: `(reload)`

The `dev` namespace provides:
- `(start)` - Start the development system
- `(stop)` - Stop the system
- `(reload)` - Reload changed namespaces via clj-reload
- `(restart)` - Stop, reload, and start

### Portal

Portal opens automatically when the dev namespace loads. Any `(tap> data)` calls will appear in the Portal UI.

## Project Structure

```
src/clj/          # Clojure source files
dev/src/clj/      # Development-only source (user.clj, dev.clj)
test/src/clj/     # Test files
resources/        # Resource files
specs/            # Living specifications
```

## Specifications

The `specs/` directory contains living documents for planning and tracking work.

### Structure

- **`specs/README.md`** - Meta document organizing current priorities
- **`specs/<name>.md`** - Individual spec files for features or concepts

### Starting a Session

At the start of a Claude session, read `specs/README.md` to understand current priorities and what to work on. The meta document links to detailed specs for active work.

### Maintaining Specs

- Create new specs as `specs/<feature-name>.md` when planning features
- Update spec files as work progresses (track done/todo items)
- Update `specs/README.md` when priorities change
- Specs are living documents—update them as understanding evolves

## REPL Evaluation

Use the clojure-eval skill to evaluate code via nREPL:

```bash
clj-nrepl-eval --discover-ports          # Find running REPLs
clj-nrepl-eval -p <PORT> "(+ 1 2 3)"     # Evaluate expression
```

### Reloading Code

**The workflow is two steps:**

1. **First, reload changed namespaces** using `(dev/reload)`:
   ```bash
   clj-nrepl-eval -p <PORT> "(dev/reload)"
   ```

2. **Then, require and test** in follow-up evaluations (no `:reload` flags needed):
   ```bash
   clj-nrepl-eval -p <PORT> "(require '[ascolais.sandestin :as s])"
   clj-nrepl-eval -p <PORT> "(s/describe dispatch)"
   ```

**Important:** Do NOT use `:reload` or `:reload-all` flags on require. The `(dev/reload)` handles namespace reloading properly via clj-reload. Plain requires after reload will pick up the fresh code.

## Running Tests

```bash
clj -X:test
```

Or from the REPL:

```clojure
(require '[clojure.test :refer [run-tests]])
(require '[ascolais.sandestin-test] :reload)
(run-tests 'ascolais.sandestin-test)
```

## Adding Dependencies

When adding new dependencies in a REPL-connected environment:

1. **Add to the running REPL first** using `clojure.repl.deps/add-lib`:
   ```clojure
   (clojure.repl.deps/add-lib 'metosin/malli {:mvn/version "0.16.4"})
   ```
   Note: The library name must be quoted.

2. **Confirm the dependency works** by requiring and testing it in the REPL.

3. **Only then add to deps.edn** once confirmed working.

This ensures dependencies are immediately available without restarting the REPL.

## Effect Schemas

Sandestin effect schemas describe **how an effect is called**, not what it returns. The schema specifies the invocation shape: the effect key and its arguments.

```clojure
;; Schema format
[:tuple [:= :effect/key] <arg-schemas...>]

;; Example: effect taking a string
::s/schema [:tuple [:= :my.fx/greet] :string]
;; Invoked as: [:my.fx/greet "Alice"]

;; Example: effect taking a set
::s/schema [:tuple [:= :my.fx/analyze] [:set :string]]
;; Invoked as: [:my.fx/analyze #{"AAPL" "GOOG"}]
```

**Do NOT** use schemas to document return types — they exist for discoverability of how to invoke effects.

## Code Style

- Follow standard Clojure conventions
- Use `cljfmt` formatting (applied automatically via hooks)
- Prefer pure functions where possible
- Use `tap>` for debugging output (appears in Portal)

## Git Commits

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>[optional scope]: <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Formatting, no code change
- `refactor`: Code restructuring without feature/fix
- `test`: Adding or updating tests
- `chore`: Maintenance, dependencies, tooling

**Examples:**
```
feat: add effect dispatch with registry merging
feat(dispatch): implement async continuation support
fix(registry): handle nil values in merge
docs: update CLAUDE.md with commit guidelines
test: add error handling tests for dispatch
```
