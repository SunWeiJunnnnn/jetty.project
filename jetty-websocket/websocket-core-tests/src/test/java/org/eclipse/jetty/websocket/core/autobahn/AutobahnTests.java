//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.autobahn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class AutobahnTests
{
    private static final Logger LOG = LoggerFactory.getLogger(AutobahnTests.class);
    private static final Path USER_DIR = Paths.get(System.getProperty("user.dir"));
    private static final String CHOWN_REPORTS_CMD = "[ -d /target/reports ] && chown -R `stat -c '%u' /target/reports` /target/reports/*";

    private Path reportDir;
    private Path fuzzingServer;
    private Path fuzzingClient;

    Path baseDir;

    @BeforeEach
    public void before() throws Exception
    {
        String workspace;
        workspace = System.getenv().get("WORKSPACE");
        LOG.info("Workspace: {}", workspace);
        LOG.info("User Dir: {}", USER_DIR);
        baseDir = USER_DIR;//(workspace != null) ? Paths.get(workspace) : USER_DIR;
        LOG.info("Base Dir: {}", baseDir);

        fuzzingServer = baseDir.resolve("fuzzingserver.json");
        assertTrue( Files.exists(fuzzingServer), fuzzingServer + " not exists");

        fuzzingClient = baseDir.resolve("fuzzingclient.json");
        assertTrue(Files.exists(fuzzingClient),  fuzzingClient + " not exists");

        reportDir = baseDir.resolve("target/reports");
        if (!Files.exists(reportDir))
            Files.createDirectory(reportDir);
    }

    @Test
    public void testClient() throws Exception
    {
        // We need to chown the generated test results so host has permissions to delete files generated by container.
        try (GenericContainer<?> container = new GenericContainer<>("crossbario/autobahn-testsuite:latest")
            .withCommand("/bin/bash", "-c", "wstest -m fuzzingserver -s /config/fuzzingserver.json ; " + CHOWN_REPORTS_CMD)
            .withExposedPorts(9001)
            .withLogConsumer(new Slf4jLogConsumer(LOG)))
        {
            container.addFileSystemBind(fuzzingServer.toString(), "/config/fuzzingserver.json", BindMode.READ_ONLY);
            container.addFileSystemBind(reportDir.toString(), "/target/reports", BindMode.READ_WRITE);
            container.start();
            Integer mappedPort = container.getMappedPort(9001);
            CoreAutobahnClient.main(new String[]{"localhost", mappedPort.toString()});
            IO.copy(reportDir.toFile(), baseDir.resolve("target/reports-client").toFile());
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("clients/index.html").toUri());
    }

    @Test
    public void testServer() throws Exception
    {
        // We need to expose the host port of the server to the Autobahn Client in docker container.
        final int port = 9001;
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        Server server = CoreAutobahnServer.startAutobahnServer(port);
        // We need to chown the generated test results so host has permissions to delete files generated by container.
        try (GenericContainer<?> container = new GenericContainer<>("crossbario/autobahn-testsuite:latest")
            .withCommand("/bin/bash", "-c", "wstest -m fuzzingclient -s /config/fuzzingclient.json ; " + CHOWN_REPORTS_CMD)
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofHours(1))))
        {
            container.addFileSystemBind(fuzzingClient.toString(), "/config/fuzzingclient.json", BindMode.READ_ONLY);
            container.addFileSystemBind(reportDir.toString(), "/target/reports", BindMode.READ_WRITE);
            container.start();
            IO.copy(reportDir.toFile(), baseDir.resolve("target/reports-server").toFile());
        }
        finally
        {
            server.stop();
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("servers/index.html").toUri());
    }
}
