package com.redhat.quayoktasyncooperator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class QuayOktaSyncReconcilerTest {

    private QuayOktaSyncReconciler reconciler;

    @BeforeEach
    void setUp() {

        reconciler = new QuayOktaSyncReconciler(mock(KubernetesClient.class));
    }

    @Test
    void defaultGroupPatternExtractsThirdSegment() throws Exception {

        Pattern pattern = Pattern.compile("^[^-]+-[^-]+-([^-]+)-[^-]+$");
        assertTrue(invokeBoolean("matchesGroupPattern", new Class<?>[]{String.class, Pattern.class},
                "prod-iowa-farmers-admin", pattern));
        assertEquals("farmers", invokeString("resolveTeamName",
                new Class<?>[]{String.class, Pattern.class, Map.class},
                "prod-iowa-farmers-admin", pattern, new TreeMap<String, String>()));
    }

    @Test
    void groupPatternRejectsMissingOrExtraSegments() throws Exception {

        Pattern pattern = Pattern.compile("^[^-]+-[^-]+-([^-]+)-[^-]+$");
        assertFalse(invokeBoolean("matchesGroupPattern", new Class<?>[]{String.class, Pattern.class},
                "farmers-admin", pattern));
        assertFalse(invokeBoolean("matchesGroupPattern", new Class<?>[]{String.class, Pattern.class},
                "prod-iowa-farmers-admin-extra", pattern));
        assertFalse(invokeBoolean("matchesGroupPattern", new Class<?>[]{String.class, Pattern.class},
                "prod-iowa--admin", pattern));
    }

    @Test
    void explicitOverrideTakesPrecedenceOverPattern() throws Exception {

        Pattern pattern = Pattern.compile("^[^-]+-[^-]+-([^-]+)-[^-]+$");
        Map<String, String> overrides = new TreeMap<>();
        overrides.put("legacy-farmers-admins", "farmers");

        assertEquals("farmers", invokeString("resolveTeamName",
                new Class<?>[]{String.class, Pattern.class, Map.class},
                "legacy-farmers-admins", pattern, overrides));
    }

    @Test
    void unmatchedGroupWithoutOverrideIsRejected() throws Exception {

        Pattern pattern = Pattern.compile("^[^-]+-[^-]+-([^-]+)-[^-]+$");
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> invoke("resolveTeamName", new Class<?>[]{String.class, Pattern.class, Map.class},
                        "legacy-farmers-admins", pattern, new TreeMap<String, String>()));
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("did not match groupNamePattern"));
    }

    @Test
    void urlHelpersNormalizeAndEncodeValues() throws Exception {

        assertEquals("https://example.okta.com", invokeString("trimSlash",
                new Class<?>[]{String.class}, "https://example.okta.com/"));
        assertEquals("team%20name", invokeString("enc", new Class<?>[]{String.class}, "team name"));
    }

    @Test
    void nextLinkReturnsNextPageAndIgnoresOtherRelations() throws Exception {

        String header = "<https://example.okta.com/api/v1/groups?after=abc>; rel=\"next\", "
                + "<https://example.okta.com/api/v1/groups>; rel=\"self\"";
        assertEquals("https://example.okta.com/api/v1/groups?after=abc",
                invokeString("nextLink", new Class<?>[]{String.class}, header));
        assertNull(invokeString("nextLink", new Class<?>[]{String.class}, ""));
    }

    @Test
    void secretValueDecodesBase64SecretData() throws Exception {

        io.fabric8.kubernetes.api.model.Secret secret = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("credentials").endMetadata()
                .addToData("okta-api-token", Base64.getEncoder().encodeToString("okta-token".getBytes(StandardCharsets.UTF_8)))
                .build();
        assertEquals("okta-token", invokeString("secretValue",
                new Class<?>[]{io.fabric8.kubernetes.api.model.Secret.class, String.class}, secret, "okta-api-token"));
    }

    @Test
    void reconcileReportsMissingRequiredConfigurationAsError() throws Exception {

        QuayOktaSync resource = new QuayOktaSync();
        resource.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("test-sync").withNamespace("default").build());
        QuayOktaSync.Spec spec = new QuayOktaSync.Spec();
        spec.oktaBaseUrl = "https://okta.example.com";
        spec.quayBaseUrl = "https://quay.example.com";
        spec.credentialsSecret = "credentials";
        spec.quayOrganization = null;
        resource.setSpec(spec);

        Context<QuayOktaSync> context = mock(Context.class);
        UpdateControl<QuayOktaSync> result = reconciler.reconcile(resource, context);

        assertEquals("Error", resource.getStatus().phase);
        assertTrue(resource.getStatus().message.contains("quayOrganization must be configured"));
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    private boolean invokeBoolean(String name, Class<?>[] parameterTypes, Object... args) throws Exception {

        return (Boolean) invoke(name, parameterTypes, args);
    }

    private String invokeString(String name, Class<?>[] parameterTypes, Object... args) throws Exception {

        return (String) invoke(name, parameterTypes, args);
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {

        Method method = QuayOktaSyncReconciler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(reconciler, args);
    }

}
