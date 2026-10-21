package com.redhat.quayoktasyncooperator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuayOktaSyncReconcilerTest {

    @Test
    void reconcileDelegatesToServiceAndReturnsStatusUpdate() throws Exception {

        QuayOktaSyncService service = mock(QuayOktaSyncService.class);
        QuayOktaSyncReconciler reconciler = new QuayOktaSyncReconciler(service);
        QuayOktaSync resource = new QuayOktaSync();
        resource.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("test-sync").withNamespace("default").build());
        QuayOktaSyncSpec spec = new QuayOktaSyncSpec();
        spec.oktaBaseUrl = "https://okta.example.com";
        spec.quayBaseUrl = "https://quay.example.com";
        spec.credentialsSecret = "credentials";
        spec.quayOrganization = "engineering";
        resource.setSpec(spec);

        KubernetesClient client = mock(KubernetesClient.class);
        Context<QuayOktaSync> context = mock(Context.class);
        when(context.getClient()).thenReturn(client);

        UpdateControl<QuayOktaSync> result = reconciler.reconcile(resource, context);

        verify(service).synchronize(resource, client);
        assertNotNull(result);
    }

}
