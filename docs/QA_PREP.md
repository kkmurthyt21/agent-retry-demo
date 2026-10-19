# Audience and Q&A preparation

The target audience is intermediate developers, backend engineers, software architects, and technical leads. Assume familiarity with APIs and a relational database. Explain message delivery, acknowledgement, and idempotency before relying on those terms. Financial messaging is the running example; no financial-network expertise or model-training knowledge is required.

DevFest’s official page describes a professional developers festival with AI and other software tracks. The actual session audience and attendance are not established. Expect a mix of implementation questions and architecture tradeoffs. The following questions are preparation scenarios, not questions already received.

Reserve minutes 37–45. Aim to answer four to six substantive questions. Give the direct answer first, use one boundary from the diagram, and stop after 30–60 seconds unless the audience needs more detail.

## Core questions to rehearse first

**1. Why use mocked AI in an AI-agent talk?**

Fixed decisions let us reproduce the same failure. The lesson is the application protocol around an agent proposal. A real model would still have to produce supported data, pass policy, and use durable state. This demonstration makes no claim about model quality.

**2. Is this really exactly once?**

Be precise about scope. The mock gateway atomically protects one intended business action by its scoped key and stores a retrievable result. Messages and calls may repeat. The whole system does not provide a universal exactly-once guarantee across arbitrary services.

**3. Why do you need an action key in addition to an event ID?**

An event ID identifies one delivered fact for a consumer. A new envelope can describe the same intended action. The action key must stay the same across envelopes, attempts, and restarts. The application derives it from tenant, workflow, and the immutable submission step. A remote service must also authenticate the caller and authorize access to cached outcomes. This demo uses one trusted local caller.

**4. What if the provider has no idempotency feature?**

Look for a reliable reconciliation contract using a stable business correlation identifier. If the outcome cannot be determined safely, persist an ambiguous outcome for review. Another automatic submission with a new identity could repeat an action that already happened.

**5. What belongs in the prompt and what belongs in the database?**

The prompt can contain reasoning context and summaries. Persist approved intent, workflow progress, action identity, attempts, deadlines, and evidence in application storage. The database must remain authoritative after context is truncated, a model changes, or the process restarts.

**6. What happens if the agent proposes a different plan during retry?**

Recovery loads the saved plan. A changed proposal needs validation, policy approval, and an explicit new plan revision or step. Reusing the previous action key with changed parameters is a conflict. A different retry attempt alone never justifies a new business key.

**7. Does JSON Schema stop unsafe actions?**

It checks declared structure and types. Policy checks permission, business limits, approved parameters, and state. In the demo, the unauthorized beneficiary has a valid contract but fails policy. Neither check proves every aspect of business correctness.

**8. Why separate validation, delivery, and processing failures?**

They lead to different decisions. Repeating invalid input usually reproduces the same rejection. A delivery failure happens before the handler starts. A processing failure happens after invocation and may have a partial effect. Preserve those distinctions when selecting retries and review paths.

## Architecture follow-ups

**9. Why isn’t an outbox enough?**

The local outbox commits workflow progress and outgoing event intent together. It cannot atomically commit a separate provider’s mutation. That provider needs its own idempotency or reconciliation contract. The crash diagram shows the two distinct transaction owners.

**10. Why two H2 databases inside one process?**

They give us genuinely separate JDBC transactions without requiring network setup. The gateway is a local mock behind an interface. That is enough to reproduce the commit/acknowledgement gap and process restart. It does not reproduce every failure of a remote service.

**11. Could a broker, workflow engine, or agent framework do this for us?**

Those tools can supply durable messaging, timers, or orchestration. Still identify where the approved intent lives, how a provider protects an external effect, and which component owns recovery. A framework’s delivery guarantee does not automatically describe an arbitrary external business action.

**12. Would this work with Kafka, Pub/Sub, or a different cloud?**

The application patterns transfer. Implement the messaging adapter using the selected platform’s actual acknowledgement, replay, ordering, and retention semantics. Keep event contracts and the provider’s action identity explicit. The example itself depends on no broker SDK.

**13. How does this scale to several workers?**

This example permits one dispatcher through an OS lock. Multiple workers need safe claim ownership, leases or fencing where appropriate, and state-version checks. Separately, the gateway must atomically handle concurrent same-key requests. That gateway boundary is covered by the concurrent JDBC test.

**14. How long should idempotency records live?**

Long enough to cover the allowed retry, replay, and late-arrival window. The correct duration depends on the business operation and provider contract. This demo does not expire gateway keys. A provider’s TTL must not silently turn a late retry into a new action.

**15. Is an optional schema field always compatible?**

No. An old consumer with `additionalProperties: false` can reject it. Define the producer/consumer direction and test the actual contracts. This application supports separate bundled `1.0` and `1.1` schemas; only `1.1` permits the optional client reference.

**16. What if policy changes after the gateway accepted the instruction?**

A policy change cannot undo an acceptance. A read can establish what happened, while a path that might create more work needs current authorization. This demo authorizes unfinished execution again and routes denial or expiry to review. Preserve the key, receipt evidence, and original intent for that review.

**17. What happens when the retry budget is exhausted?**

The engine records the failure and moves to `NEEDS_REVIEW`. Counters and deadlines survive restart, so a new process or envelope cannot grant unlimited attempts. If a provider may already have acted, review must reconcile that outcome before permitting another mutation. Read-only reconciliation can be designed separately from a mutation budget.

**18. What if only verification fails?**

Retry verification within its own saved budget. The accepted instruction and local receipt remain recorded. The Executor is already complete and is not rerun. The automated tests exercise this boundary separately from the main crash scenario.

## Scope and evidence questions

**19. What did you actually test?**

Forty-five automated cases passed on October 18: 42 unit/regression cases and three packaged integration tests. They include real child-JVM halt and restart, socket-denied execution with a negative-control probe, concurrent same-key gateway requests, transaction rollback, stale state, policy, versions, and persisted retry limits. The command transcript and test summary are in the companion materials.

**20. Does this submit a real payment or implement SWIFT/ISO 20022?**

No real payment is submitted. The example uses synthetic references and a custom JSON contract. It demonstrates acceptance by the mock gateway. Settlement, network-message conformance, and regulated production controls have their own requirements and are outside this session.

**21. How does this relate to your AWS article?**

The published article supplies earlier work on versioned event contracts and a publication gate. This session follows the workflow further through durable execution, policy, partial failure, and restart recovery. The offline Java implementation makes those boundaries reproducible onstage.

**22. Why separate Planner, Executor, and Verifier?**

They own different evidence and authority. The Planner proposes intent. Application policy authorizes it. The Executor requests an external action. The Verifier checks the recorded outcome. They can be logical roles in one application, as they are here.

**23. What is the overhead or throughput?**

This is a correctness demonstration, not a benchmark. It uses small synthetic fixtures and immediate H2 flushing to expose the boundary clearly. Measure the actual workload and consistency requirements before drawing performance conclusions.

**24. What would you add for production?**

Start with the real business operation and provider contract. Specify authentication, authorization, data handling, worker ownership, retry windows, audit retention, and ambiguous-outcome review. Then test that application’s failure boundaries. Avoid converting the answer into a cloud deployment walkthrough.

## When a question exceeds the example

State the boundary honestly: “The demonstration tests process restart with these durable files. I would need to check that provider’s actual contract before promising the same outcome there.” Do not invent an employer implementation, adoption metric, financial protocol guarantee, or benchmark.

Public event context checked October 18, 2026: https://gdg.community.dev/events/details/google-gdg-hudson-presents-devfest-troy-26/
