package com.redhat.quayoktasyncooperator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

@Group("quayoktaysync.io")
@Version("v1alpha1")
public class QuayOktaSync extends CustomResource<QuayOktaSyncSpec, QuayOktaSyncStatus> implements Namespaced {

    @Override
    public String toString() {

        return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
    }
}
