package com.simulator.policy;
import com.simulator.model.Client;
import com.simulator.model.Request;
import com.simulator.model.RequestType;
import com.simulator.model.ViolationLevel;
import javafx.collections.ObservableList;

public interface RatePolicy {

    /**
     * Outcome of evaluating one request against the policy.
     *
     * <p>{@code allowed} answers "may this request through?" and is the only
     * thing the caller should use to decide whether to serve or block.
     * {@code level} is the client's threat level, which is derived purely from
     * the accumulated violation score. The two are deliberately separate: a
     * request can be blocked while the client is still only a low risk, and a
     * client can sit at CRITICAL purely because of its score.
     */
    record Decision(boolean allowed, ViolationLevel level) {

        public static Decision allowed(ViolationLevel level) {
            return new Decision(true, level);
        }

        public static Decision blocked(ViolationLevel level) {
            return new Decision(false, level);
        }
    }

    /**
     * Evaluates a request of {@code type} from {@code client}.
     *
     * <p>Only requests from the same client <em>and</em> of the same
     * {@code type} are counted, because the configured limit is per client per
     * request type.
     */
    Decision evaluate(Client client, RequestType type, ObservableList<Request> requests);

}
