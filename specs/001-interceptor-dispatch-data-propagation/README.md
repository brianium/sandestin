---
title: "Continuous Context Flow for Interceptors"
status: completed
date: 2024-01-15
priority: 10
---

# Continuous Context Flow for Interceptors

## Overview

Context modifications made by interceptors were discarded between phases. The dispatch flow rebuilt context at each phase boundary, losing modifications from prior phases. This prevented interceptors from enriching the context that flows through the system.

## Goals

- Enable interceptors to modify `dispatch-data` and have changes propagate through all phases
- Follow the [Nexus](https://github.com/brianium/nexus) pattern where context flows continuously
- Support observability, flow control, and result transformation in after-phases
- Maintain backward compatibility with existing interceptors

## Non-Goals

- Changing the interceptor API
- Adding new interceptor hooks (using existing before-/after- hooks)

## Key Decisions

See [research.md](research.md) for detailed analysis.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Context flow | Single context map through all phases | Nexus alignment, simpler than explicit key propagation |
| After-phase order | LIFO (reverse) | Queue/stack pattern from Nexus |
| Context structure | Additive keys per phase | No removal, backward compatible |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [x] Phase 1: Refactor dispatch.clj for continuous context flow
- [x] Phase 2: Add after-phase context propagation
- [x] Phase 3: Testing & validation

## Motivation

Discovered while building [sfere](https://github.com/brianium/sfere), a connection management library for twk/Datastar applications.

**The scenario:**
1. twk dispatches `[[:ascolais.twk/connection]]` to look for an existing SSE connection
2. sfere's interceptor should inject a stored connection into `dispatch-data`
3. twk's `::twk/connection` effect handler reads from `dispatch-data` to return the connection

**What happened before this fix:**
1. sfere's `before-dispatch` interceptor sets `ctx[:dispatch-data ::twk/connection]`
2. Context was rebuilt; twk's effect handler received original `dispatch-data`
3. Effect returned `nil`, twk created a new SSE instead of reusing
