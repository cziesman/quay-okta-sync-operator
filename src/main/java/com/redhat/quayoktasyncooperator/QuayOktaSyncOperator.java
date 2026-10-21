package com.redhat.quayoktasyncooperator;

import java.io.IOException;

import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;

public class QuayOktaSyncOperator {

    private static final Logger log = LoggerFactory.getLogger(QuayOktaSyncOperator.class);

    public static void main(String[] args) throws IOException {

        Operator operator = new Operator();
        operator.register(new QuayOktaSyncReconciler());
        operator.start();
        log.info("Quay-Okta Sync Operator started");
        new FtBasic(new TkFork(new FkRegex("/health", "ALL GOOD.")), 8080).start(Exit.NEVER);
    }

}
