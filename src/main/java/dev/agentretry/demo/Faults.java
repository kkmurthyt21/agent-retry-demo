package dev.agentretry.demo;

/** Explicit fault boundaries. The default implementation does nothing. */
@FunctionalInterface
interface Faults {
    enum Point { BEFORE_PLAN_COMMIT, BEFORE_DELIVERY, BEFORE_EXECUTION,
        GATEWAY_BETWEEN_WRITES, AFTER_GATEWAY_COMMIT, AFTER_LOCAL_RESULT_COMMIT, BEFORE_VERIFICATION }
    void at(Point point, String workflowId);
    Faults NONE = (point, workflowId) -> { };
}
