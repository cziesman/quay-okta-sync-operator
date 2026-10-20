FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.21
WORKDIR /opt/operator
COPY target/quay-okta-sync-operator-0.1.0.jar app.jar
USER 185
ENTRYPOINT ["java","-jar","/opt/operator/app.jar"]
