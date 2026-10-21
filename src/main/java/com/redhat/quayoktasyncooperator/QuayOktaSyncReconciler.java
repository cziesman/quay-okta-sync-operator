package com.redhat.quayoktasyncooperator;

import java.time.Duration;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerConfiguration(name = "quay-okta-sync")
public class QuayOktaSyncReconciler implements Reconciler<QuayOktaSync> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final QuayOktaSyncService syncService;

    public QuayOktaSyncReconciler() {

        this(new QuayOktaSyncService());
    }

    QuayOktaSyncReconciler(QuayOktaSyncService syncService) {

        this.syncService = syncService;
    }

    @Override
    public UpdateControl<QuayOktaSync> reconcile(
            QuayOktaSync resource, Context<QuayOktaSync> context) throws Exception {

        int configuredInterval = resource.getSpec() == null || resource.getSpec().intervalSeconds == null
                ? 900
                : resource.getSpec().intervalSeconds;
        long intervalSeconds = Math.max(60, configuredInterval);
        log.info("Reconciling QuayOktaSync {} in namespace {}", resource.getMetadata().getName(), resource.getMetadata().getNamespace());
        syncService.synchronize(resource, context.getClient());
        return UpdateControl.patchStatus(resource).rescheduleAfter(Duration.ofSeconds(intervalSeconds));
    }

}
