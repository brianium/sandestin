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
```

## REPL Evaluation

Use the clojure-eval skill to evaluate code via nREPL:

```bash
clj-nrepl-eval --discover-ports          # Find running REPLs
clj-nrepl-eval -p <PORT> "(+ 1 2 3)"     # Evaluate expression
```

Always use `:reload` when requiring namespaces to pick up changes:

```bash
clj-nrepl-eval -p <PORT> "(require '[ascolais.sandestin] :reload)"
```

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
