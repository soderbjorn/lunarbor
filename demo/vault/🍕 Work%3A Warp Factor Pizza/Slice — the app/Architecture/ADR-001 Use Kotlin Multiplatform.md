# ADR-001 Use Kotlin Multiplatform

**Status:** Accepted · **Date:** 2025-05-12 · **Deciders:** Maya, Priya, Lars

## Context

We have one Android developer (Maya), one iOS developer (Priya) and a backlog that thinks we have twelve. Every feature was being written twice, and the pizza price rounding was different on each platform. Customers noticed. One customer wrote a *spreadsheet*.

## Decision

Share everything that isn't UI in a Kotlin Multiplatform module:

- domain models and business rules (pricing, delivery zones, the topping compatibility matrix)
- networking with Ktor
- persistence with SQLDelight
- view models, exposed to SwiftUI via SKIE

UI stays fully native: Jetpack Compose and SwiftUI.

## Consequences

- One implementation of the pricing rules. The spreadsheet customer is now our biggest fan.
- Priya has to read Kotlin. She says it's "Swift with a Scandinavian accent".
- Gradle is now everyone's problem. See ADR-004.
