package com.redhat.quayoktasyncooperator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;

public class Main {

    public static void main(String[] args) {

        KubernetesClient client = new KubernetesClientBuilder().build();
        Operator operator = new Operator(configurationServiceOverrider ->
                configurationServiceOverrider.withKubernetesClient(client));
        operator.register(new QuayOktaSyncReconciler(client));
        operator.start();
        Runtime.getRuntime().addShutdownHook(new Thread(operator::stop));
    }

}
