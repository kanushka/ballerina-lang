/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.langserver.extensions.ballerina.connector;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.PackageName;
import io.ballerina.projects.PackageOrg;
import io.ballerina.projects.PackageVersion;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.Settings;
import io.ballerina.projects.directory.ProjectLoader;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.environment.PackageResolver;
import io.ballerina.projects.environment.ResolutionResponse;
import io.ballerina.projects.repos.TempDirCompilationCache;
import org.ballerinalang.central.client.CentralAPIClient;
import org.ballerinalang.central.client.model.ConnectorInfo;
import org.ballerinalang.diagramutil.connector.generator.ConnectorGenerator;
import org.ballerinalang.diagramutil.connector.models.connector.Connector;
import org.ballerinalang.langserver.LSClientLogger;
import org.ballerinalang.langserver.commons.LanguageServerContext;
import org.ballerinalang.langserver.exception.LSConnectorException;
import org.eclipse.lsp4j.Position;
import org.wso2.ballerinalang.compiler.util.ProjectDirConstants;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.ballerina.cli.utils.CentralUtils.readSettings;
import static io.ballerina.projects.util.ProjectUtils.getAccessTokenOfCLI;
import static io.ballerina.projects.util.ProjectUtils.initializeProxy;

/**
 * Implementation of the BallerinaConnectorService.
 *
 * @since 2.0.0
 */
public class BallerinaConnectorServiceImpl implements BallerinaConnectorService {

    private final ConnectorExtContext connectorExtContext;
    private final LSClientLogger clientLogger;

    public BallerinaConnectorServiceImpl(LanguageServerContext serverContext) {
        this.connectorExtContext = new ConnectorExtContext();
        this.clientLogger = LSClientLogger.getInstance(serverContext);
    }

    @Override
    public CompletableFuture<BallerinaConnectorListResponse> connectors(BallerinaConnectorListRequest request) {
        try {
            // fetch ballerina central connectors
            Settings settings = readSettings();
            CentralAPIClient client = new CentralAPIClient(RepoUtils.getRemoteRepoURL(),
                    initializeProxy(settings.getProxy()),
                    getAccessTokenOfCLI(settings));
            JsonElement connectorSearchResult = client.getConnectors(request.getPackageName(),
                    "any",
                    RepoUtils.getBallerinaVersion());
            CentralConnectorListResult centralConnectorListResult = new Gson().fromJson(
                    connectorSearchResult.getAsString(), CentralConnectorListResult.class);

            // fetch local project connectors
            Path filePath = Paths.get(request.getTargetFile());
            List<Connector> localConnectors = fetchLocalConnectors(filePath, false);

            BallerinaConnectorListResponse connectorListResponse = new BallerinaConnectorListResponse(
                    centralConnectorListResult.getConnectors(), localConnectors);
            return CompletableFuture.supplyAsync(() -> connectorListResponse);

        } catch (Exception e) {
            String msg = "Operation 'ballerinaConnector/connectors' failed!";
            this.clientLogger.logError(this.connectorExtContext, msg, e, null, (Position) null);
        }

        return CompletableFuture.supplyAsync(BallerinaConnectorListResponse::new);
    }

    private Path resolveBalaPath(String org, String pkgName, String version) throws LSConnectorException {
        Environment environment = EnvironmentBuilder.buildDefault();

        PackageDescriptor packageDescriptor = PackageDescriptor.from(
                PackageOrg.from(org), PackageName.from(pkgName), PackageVersion.from(version));

        PackageResolver packageResolver = environment.getService(PackageResolver.class);
        List<ResolutionResponse> resolutionResponses = packageResolver.resolvePackages(
                Collections.singletonList(packageDescriptor), false);
        ResolutionResponse resolutionResponse = resolutionResponses.stream().findFirst().get();

        if (resolutionResponse.resolutionStatus().equals(ResolutionResponse.ResolutionStatus.RESOLVED)) {
            Package resolvedPackage = resolutionResponse.resolvedPackage();
            if (resolvedPackage != null) {
                return resolvedPackage.project().sourceRoot();
            }
        }
        throw new LSConnectorException("No bala project found for package '"
                + packageDescriptor.toString() + "'");
    }

    /**
     * Fetch ballerina connector form local file.
     *
     * @param filePath file path
     * @param detailed detailed connector out put or not
     * @return connector list
     * @throws IOException
     */
    private List<Connector> fetchLocalConnectors(Path filePath, boolean detailed) throws IOException {
        ProjectEnvironmentBuilder defaultBuilder = ProjectEnvironmentBuilder.getDefaultBuilder();
        defaultBuilder.addCompilationCacheFactory(TempDirCompilationCache::from);
        Project balaProject = ProjectLoader.loadProject(filePath, defaultBuilder);

        List<Connector> connectors = ConnectorGenerator.getProjectConnectors(balaProject, detailed);

        List<Connector> localConnectors = new ArrayList<>();
        for (Connector conn : connectors) {
            localConnectors.add(conn);
        }

        return localConnectors;
    }

    @Override
    public CompletableFuture<JsonObject> connector(BallerinaConnectorRequest request) {
        JsonObject connector = null;

        if (request.getConnectorId() != null) {
            // Fetch connector by connector Id.
            try {
                Settings settings = readSettings();
                CentralAPIClient client = new CentralAPIClient(RepoUtils.getRemoteRepoURL(),
                        initializeProxy(settings.getProxy()),
                        getAccessTokenOfCLI(settings));
                connector = client.getConnector(request.getConnectorId(),
                        "any",
                        RepoUtils.getBallerinaVersion());

            } catch (Exception e) {
                String msg = "Operation 'ballerinaConnector/connector' failed!";
                this.clientLogger.logError(this.connectorExtContext, msg, e, null, (Position) null);
            }
        }

        if (connector == null && request.isFullConnector()) {
            // Fetch connector by connector FQN.
            try {
                Settings settings = readSettings();
                CentralAPIClient client = new CentralAPIClient(RepoUtils.getRemoteRepoURL(),
                        initializeProxy(settings.getProxy()),
                        getAccessTokenOfCLI(settings));
                ConnectorInfo connectorInfo = new ConnectorInfo(request.getOrgName(), request.getPackageName(),
                        request.getModuleName(), request.getVersion(), request.getName());
                connector = client.getConnector(connectorInfo, "any", RepoUtils.getBallerinaVersion());

            } catch (Exception e) {
                String msg = "Operation 'ballerinaConnector/connector' failed!";
                this.clientLogger.logError(this.connectorExtContext, msg, e, null, (Position) null);
            }
        }

        if (connector == null && request.getTargetFile() != null) {
            // Generate local connector metadata.
            try {
                Path filePath = Paths.get(request.getTargetFile());
                List<Connector> localConnectors = fetchLocalConnectors(filePath, false);
                for (Connector conn : localConnectors) {
                    if (conn.name.equals(request.getName())) {
                        Gson gson = new Gson();
                        connector = gson.fromJson(gson.toJson(conn), JsonObject.class);
                        break;
                    }
                }

            } catch (Exception e) {
                String connectorId = getCacheableKey(request.getOrgName(), request.getModuleName(),
                        request.getVersion());
                String msg = "Operation 'ballerinaConnector/connector' for " + connectorId + ":" +
                        request.getName() + " failed!";
                this.clientLogger.logError(this.connectorExtContext, msg, e, null, (Position) null);
            }
        }

        if (connector == null) {
            // Generate connector metadata by connector FQN.
            try {
                Path balaPath = resolveBalaPath(request.getOrgName(), request.getModuleName(), request.getVersion());
                List<Connector> connectors = fetchLocalConnectors(balaPath, true);
                for (Connector conn : connectors) {
                    if (conn.name.equals(request.getName())) {
                        Gson gson = new Gson();
                        connector = gson.fromJson(gson.toJson(conn), JsonObject.class);
                        break;
                    }
                }

            } catch (Exception e) {
                String connectorId = getCacheableKey(request.getOrgName(), request.getModuleName(),
                        request.getVersion());
                String msg = "Operation 'ballerinaConnector/connector' for " + connectorId + ":" +
                        request.getName() + " failed!";
                this.clientLogger.logError(this.connectorExtContext, msg, e, null, (Position) null);
            }
        }

        JsonObject finalConnector = connector;
        return CompletableFuture.supplyAsync(() -> finalConnector);
    }

    private String getCacheableKey(String orgName, String moduleName, String version) {
        return orgName + "_" + moduleName + "_" +
                (version.isEmpty() ? ProjectDirConstants.BLANG_PKG_DEFAULT_VERSION : version);
    }

}
