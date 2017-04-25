/*
 *
 * *********************************************************************
 * fsdevtools
 * %%
 * Copyright (C) 2016 e-Spirit AG
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * *********************************************************************
 *
 */

package com.espirit.moddev.cli.commands.server;

import com.espirit.moddev.cli.results.SimpleResult;
import com.espirit.moddev.serverrunner.NativeServerRunner;
import com.espirit.moddev.serverrunner.ServerProperties;
import com.espirit.moddev.serverrunner.ServerRunner;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.help.Examples;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

@Command(name = "restart", groupNames = "server", description = "Restarts a FirstSpirit server. Starts, even if it's not already running. Needs an fs-access.jar on the classpath.")
@Examples(examples =
        {
                "server restart",
                "server restarts -h localhost -p 9000"
        },
        descriptions = {
                "Simply restarts the server running on localhost:8000.",
                "Simply restarts the server running on localhost:9000."
        })
/**
 * Command class that can restart a FirstSpirit server. Uses stop and start functionality from ServerRunner implementations to achieve this.
 */
public class ServerRestartCommand extends ServerStartCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRestartCommand.class);

    @Override
    public SimpleResult<String> call() throws Exception {
        Optional<File> accessJarFileFromClasspath = ServerProperties.getAccessJarFileFromClasspath();
        ServerProperties serverProperties = ServerProperties.builder()
                .serverHost(getHost())
                .serverPort(getPort())
                .serverAdminPw(getPassword())
                .firstSpiritJar(accessJarFileFromClasspath.isPresent() ? accessJarFileFromClasspath.get() : null)
                .build();
        ServerRunner serverRunner = new NativeServerRunner(serverProperties);

        boolean stopped = serverRunner.stop();
        LOGGER.info(stopped ? "Server stopped!" : "Server couldn't be stopped!");

        return super.call();
    }
}
