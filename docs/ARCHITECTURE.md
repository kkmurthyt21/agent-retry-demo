# Architecture

The current build4 architecture, registered tools, event stages, state ownership, local transaction boundaries and both failure scenarios are documented in [AGENTIC_ARCHITECTURE.md](AGENTIC_ARCHITECTURE.md).

The four local JVMs are the Java agent/launcher, durable broker, workflow application and mock provider. Gemini is remote in live mode. Executor and Verifier are deterministic classes in the workflow application; Planner/Executor/Verifier are logical responsibilities. The application uses Spring AI without Spring Boot.

The earlier offline architecture is preserved in the [v1.0.0 documentation](https://github.com/kkmurthyt21/agent-retry-demo/blob/v1.0.0/docs/ARCHITECTURE.md).
