package io.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;

/**
 * Kubernetes-specific implementation of {@link hudson.slaves.NoDelayProvisionerStrategy} which
 * provisions new nodes immediately as tasks enter the queue.
 *
 * <p>This strategy extends Jenkins core's NoDelayProvisionerStrategy and filters to only handle
 * {@link KubernetesCloud} instances. This is appropriate because Kubernetes pods can be started
 * and destroyed quickly, making immediate provisioning efficient and cost-effective.
 *
 * <p>This implementation leverages all the improvements in Jenkins core including:
 * <ul>
 *   <li>CloudProvisioningLimits - prevents over-provisioning</li>
 *   <li>ProvisioningMetrics - tracks provisioning performance</li>
 *   <li>QueueItemTracker - links queue items to planned nodes</li>
 *   <li>CloudStateManager - intelligent cloud ordering for optimal utilization</li>
 * </ul>
 *
 * @author <a href="mailto:root@junwuhui.cn">runzexia</a>
 * @since 2.0.0
 */
@Extension(ordinal = 100)
public class KubernetesNoDelayProvisionerStrategy extends hudson.slaves.NoDelayProvisionerStrategy {

    private static final Logger LOGGER = Logger.getLogger(KubernetesNoDelayProvisionerStrategy.class.getName());
    private static final boolean DISABLE_NODELAY_PROVISIONING =
            Boolean.valueOf(System.getProperty("io.jenkins.plugins.kubernetes.disableNoDelayProvisioning"));

    @NonNull
    @Override
    public NodeProvisioner.StrategyDecision apply(@NonNull NodeProvisioner.StrategyState strategyState) {
        if (DISABLE_NODELAY_PROVISIONING) {
            LOGGER.log(Level.FINE, "Provisioning not complete, Kubernetes NoDelayProvisionerStrategy is disabled");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        // Delegate to core's implementation which has all the advanced features
        return super.apply(strategyState);
    }

    /**
     * Filters to only process KubernetesCloud instances.
     *
     * <p>This ensures that the Kubernetes-specific no-delay provisioning strategy only handles
     * Kubernetes clouds, allowing other cloud types to be handled by their own strategies or
     * core's generic strategy.
     *
     * @param cloud the cloud to check
     * @param label the label being provisioned for (may be null)
     * @return true if this is a KubernetesCloud, false otherwise
     */
    @Override
    protected boolean shouldProcessCloud(@NonNull Cloud cloud, Label label) {
        return cloud instanceof KubernetesCloud;
    }

    /**
     * QueueListener that triggers immediate provisioning when tasks enter the queue.
     *
     * <p>This listener is critical for the "no delay" behavior. When a build item becomes
     * buildable, it immediately triggers the NodeProvisioner to review provisioning strategies
     * rather than waiting for the next periodic check. This ensures Kubernetes pods are
     * provisioned within milliseconds of demand appearing.
     *
     * <p>Note: This is different from Jenkins core's ProvisioningQueueListener which only
     * handles cleanup tracking. Both listeners work together:
     * <ul>
     *   <li>FastProvisioning (this class) - triggers immediate provisioning review</li>
     *   <li>ProvisioningQueueListener (core) - tracks queue items for cleanup</li>
     * </ul>
     */
    @Extension
    public static class FastProvisioning extends QueueListener {

        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            if (DISABLE_NODELAY_PROVISIONING) {
                return;
            }
            final Jenkins jenkins = Jenkins.get();
            final Label label = item.getAssignedLabel();
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof KubernetesCloud && cloud.canProvision(new Cloud.CloudState(label, 0))) {
                    final NodeProvisioner provisioner =
                            (label == null ? jenkins.unlabeledNodeProvisioner : label.nodeProvisioner);
                    provisioner.suggestReviewNow();
                }
            }
        }
    }
}
