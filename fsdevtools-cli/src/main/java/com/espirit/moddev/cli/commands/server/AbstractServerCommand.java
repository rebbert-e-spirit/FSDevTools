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

import com.espirit.moddev.serverrunner.ServerProperties;
import com.github.rvesse.airline.annotations.Option;

class AbstractServerCommand {
    @Option(name = {"-h", "--host"}, description = "The hostname to use for the FirstSpirit server. The default is 'localhost'.")
    private String host = "localhost";
    @Option(name = {"-p", "-port", "--port"}, description = "The port to use for the FirstSpirit server. The default is '8000'.")
    private Integer port = 8000;
    @Option(name = {"-pw", "-password"}, description = "The admin password to be used. The default is 'Admin'.")
    @SuppressWarnings("squid:S2068")
    private String password = "Admin";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Initializes this class's fields based on the given {@link ServerProperties}
     * @param serverProperties the configuration to source from
     */
    public void initializeFromProperties(ServerProperties serverProperties) {
        setHost(serverProperties.getServerHost());
        setPort(serverProperties.getServerPort());
        setPassword(serverProperties.getServerAdminPw());
    }

}
