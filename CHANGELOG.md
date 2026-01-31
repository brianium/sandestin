# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] - 2026-01-31

### Added

- Deep grep for searching all metadata across the effect registry

## [0.5.0] - 2026-01-18

### Added

- Dispatch initializer for component systems (e.g., Integrant, Component)

## [0.4.0] - 2026-01-17

### Added

- Continuous context flow for interceptors - context now flows through the entire interceptor chain

## [0.3.0] - 2026-01-17

### Added

- System override support to continuation dispatch (3-arity dispatch)

## [0.2.0] - 2026-01-13

### Added

- Claude Code skills for `fx-explore` and `fx-registry`
- Placeholder interpolation between action expansions (self-preserving placeholders)
- GitHub Actions workflow for running tests on PRs

## [0.1.0] - 2026-01-11

### Added

- Core effect dispatch system
- Actions and placeholders for effect composition
- Interceptor chain for effect processing
- Discoverability functions (`describe`, `effects`, `actions`)
- System schema declarations for effect validation

[0.6.0]: https://github.com/ascolais/sandestin/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ascolais/sandestin/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/ascolais/sandestin/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ascolais/sandestin/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ascolais/sandestin/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ascolais/sandestin/releases/tag/v0.1.0
