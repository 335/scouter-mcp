package scouter.mcp.client;

import org.junit.jupiter.api.Test;
import scouter.mcp.error.McpError;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRetryTest {

    @Test
    void returnsResultWithoutRecoveryWhenActionSucceeds() {
        AtomicInteger recoveries = new AtomicInteger();
        String out = SessionRetry.execute(() -> "ok", recoveries::incrementAndGet);
        assertThat(out).isEqualTo("ok");
        assertThat(recoveries).hasValue(0);
    }

    @Test
    void recoversOnceThenRetriesWhenSessionExpired() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        String out = SessionRetry.execute(() -> {
            if (attempts.getAndIncrement() == 0) {
                throw McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, "invalid session");
            }
            return "ok-after-relogin";
        }, recoveries::incrementAndGet);
        assertThat(out).isEqualTo("ok-after-relogin");
        assertThat(attempts).hasValue(2);
        assertThat(recoveries).hasValue(1);
    }

    @Test
    void propagatesWhenSecondAttemptAlsoFails() {
        AtomicInteger recoveries = new AtomicInteger();
        assertThatThrownBy(() -> SessionRetry.execute(() -> {
            throw McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, "invalid session");
        }, recoveries::incrementAndGet))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).code() == McpError.Code.SCOUTER_AUTH_FAILED);
        assertThat(recoveries).hasValue(1); // recovered exactly once, no infinite loop
    }

    @Test
    void doesNotRetryNonAuthErrors() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        assertThatThrownBy(() -> SessionRetry.execute(() -> {
            attempts.incrementAndGet();
            throw McpError.of(McpError.Code.INVALID_INPUT, "bad");
        }, recoveries::incrementAndGet))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).code() == McpError.Code.INVALID_INPUT);
        assertThat(attempts).hasValue(1);
        assertThat(recoveries).hasValue(0);
    }
}
