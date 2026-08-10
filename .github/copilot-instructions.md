# Repository Instructions

This repository is a portfolio project for a secure document RAG platform.

## Project goals

- Build a secure document search and RAG platform.
- Use Spring Boot and Spring AI for backend and AI features.
- Use React and TypeScript for the frontend.
- Apply authentication and document-level authorization.
- Add asynchronous document indexing, caching, monitoring, and deployment features incrementally.

## Development principles

- Prefer simple architecture before introducing distributed components.
- Do not introduce Kafka, Redis, or microservices unless the current task requires them.
- Never commit API keys, passwords, access tokens, or other secrets.
- Keep backend and frontend responsibilities separated.
- Add or update tests when business logic changes.
- Handle error cases explicitly.
- Record significant architectural decisions in the docs directory.
- Do not modify unrelated files unless required for the task.

## Git workflow

- `main` is the stable release branch.
- `develop` is the integration branch.
- Feature branches should be created from `develop`.
- Feature pull requests should target `develop`.
- Release pull requests should target `main`.
- Do not push application changes directly to `main` or `develop`.

## Documentation

When making a significant architectural decision, document:

1. The problem.
2. The alternatives considered.
3. The selected approach.
4. Why it was selected.
5. Known tradeoffs.

## Current project status

The project is currently in the initial planning and environment setup stage.

Do not assume that Spring Boot, Spring AI, React, Kafka, Redis, or other framework versions have already been finalized.