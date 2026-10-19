package dev.buhanzaz.rwms.platform.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class RwmsAggregateCheckpointPolicyTest {

    private final RwmsAggregateCheckpointPolicy policy = new RwmsAggregateCheckpointPolicy();

    @Test
    void appliesTheNextVersionAndTreatsOlderVersionsAsDuplicates() {
        var initial = RwmsAggregateCheckpointPolicy.Checkpoint.initial();
        var first = policy.evaluate(initial, 0);

        assertThat(first.action()).isEqualTo(RwmsAggregateCheckpointPolicy.Action.APPLY_NEXT);
        assertThat(first.checkpoint()).isEqualTo(RwmsAggregateCheckpointPolicy.Checkpoint.active(0));
        assertThat(first.expectedVersion()).isEqualTo(0);

        var next = policy.evaluate(first.checkpoint(), 1);
        assertThat(next.action()).isEqualTo(RwmsAggregateCheckpointPolicy.Action.APPLY_NEXT);
        assertThat(next.checkpoint()).isEqualTo(RwmsAggregateCheckpointPolicy.Checkpoint.active(1));

        var duplicate = policy.evaluate(next.checkpoint(), 1);
        assertThat(duplicate.action()).isEqualTo(RwmsAggregateCheckpointPolicy.Action.DUPLICATE);
        assertThat(duplicate.checkpoint()).isEqualTo(next.checkpoint());
        assertThat(duplicate.expectedVersion()).isEqualTo(2);
    }

    @Test
    void quarantinesAGapAndBlocksEveryLaterEffectUntilReconciliation() {
        var gap = policy.evaluate(RwmsAggregateCheckpointPolicy.Checkpoint.active(4), 7);

        assertThat(gap.action()).isEqualTo(RwmsAggregateCheckpointPolicy.Action.QUARANTINE_GAP);
        assertThat(gap.expectedVersion()).isEqualTo(5);
        assertThat(gap.checkpoint().blocked()).isTrue();
        assertThat(gap.checkpoint().gapExpectedVersion()).isEqualTo(5);
        assertThat(gap.checkpoint().quarantinedVersion()).isEqualTo(7);

        assertThat(policy.evaluate(gap.checkpoint(), 5).action())
                .isEqualTo(RwmsAggregateCheckpointPolicy.Action.BLOCKED);
        assertThat(policy.evaluate(gap.checkpoint(), 7).action())
                .isEqualTo(RwmsAggregateCheckpointPolicy.Action.BLOCKED);
        assertThat(policy.evaluate(gap.checkpoint(), 8).action())
                .isEqualTo(RwmsAggregateCheckpointPolicy.Action.BLOCKED);
    }

    @Test
    void reconciliationRequiresTheMissingPrefixThenAllowsTheQuarantinedVersion() {
        var blocked = policy.evaluate(RwmsAggregateCheckpointPolicy.Checkpoint.active(4), 7).checkpoint();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.reconcile(blocked, 5));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.reconcile(blocked, 7));

        var reconciled = policy.reconcile(blocked, 6);
        assertThat(reconciled).isEqualTo(RwmsAggregateCheckpointPolicy.Checkpoint.active(6));
        assertThat(policy.evaluate(reconciled, 7).action())
                .isEqualTo(RwmsAggregateCheckpointPolicy.Action.APPLY_NEXT);
    }

    @Test
    void validatesVersionsAndNeverOverflowsWhenFindingTheNextVersion() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.evaluate(RwmsAggregateCheckpointPolicy.Checkpoint.initial(), -1));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RwmsAggregateCheckpointPolicy.Checkpoint.active(-1));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> policy.evaluate(
                        RwmsAggregateCheckpointPolicy.Checkpoint.active(Long.MAX_VALUE), Long.MAX_VALUE));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> RwmsAggregateCheckpointPolicy.Checkpoint.blocked(
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
