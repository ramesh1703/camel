/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.catalog.maven;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.grape.Grape;
import groovy.lang.GroovyClassLoader;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.CollectionStringBuffer;
import org.apache.camel.catalog.connector.CamelConnectorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.catalog.maven.ComponentArtifactHelper.extractComponentJavaType;
import static org.apache.camel.catalog.maven.ComponentArtifactHelper.loadComponentJSonSchema;
import static org.apache.camel.catalog.maven.ComponentArtifactHelper.loadComponentProperties;
import static org.apache.camel.catalog.maven.ConnectorArtifactHelper.loadConnectorJSonSchema;

/**
 * Default {@link MavenArtifactProvider} which uses Groovy Grape to download the artifact.
 */
public class DefaultMavenArtifactProvider implements MavenArtifactProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMavenArtifactProvider.class);

    public void addMavenRepository(String name, String url) {
        Map<String, Object> repo = new HashMap<>();
        repo.put("name", name);
        repo.put("root", url);
        Grape.addResolver(repo);
    }

    @Override
    public boolean addArtifactToCatalog(CamelCatalog camelCatalog, CamelConnectorCatalog camelConnectorCatalog,
                                        String groupId, String artifactId, String version) {
        try {
            Grape.setEnableAutoDownload(true);

            final ClassLoader classLoader = new GroovyClassLoader();

            Map<String, Object> param = new HashMap<>();
            param.put("classLoader", classLoader);
            param.put("group", groupId);
            param.put("module", artifactId);
            param.put("version", version);

            LOG.debug("Downloading {}:{}:{}", groupId, artifactId, version);
            Grape.grab(param);

            // the classloader can load content from the downloaded JAR
            boolean found = false;
            if (camelCatalog != null) {
                found |= scanCamelComponents(camelCatalog, classLoader);
            }
            if (camelConnectorCatalog != null) {
                found |= scanCamelConnectors(camelConnectorCatalog, classLoader, groupId, artifactId, version);
            }

            return found;
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean scanCamelComponents(CamelCatalog camelCatalog, ClassLoader classLoader) {
        boolean found = false;
        // is there any custom Camel components in this library?
        Properties properties = loadComponentProperties(classLoader);
        if (properties != null) {
            String components = (String) properties.get("components");
            if (components != null) {
                String[] part = components.split("\\s");
                for (String scheme : part) {
                    if (!camelCatalog.findComponentNames().contains(scheme)) {
                        // find the class name
                        String javaType = extractComponentJavaType(classLoader, scheme);
                        if (javaType != null) {
                            String json = loadComponentJSonSchema(classLoader, scheme);
                            if (json != null) {
                                LOG.debug("Adding component: {}", scheme);
                                camelCatalog.addComponent(scheme, javaType, json);
                                found = true;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    protected boolean scanCamelConnectors(CamelConnectorCatalog camelConnectorCatalog, ClassLoader classLoader,
                                          String groupId, String artifactId, String version) {
        boolean found = false;

        String[] json = loadConnectorJSonSchema(classLoader);
        if (json != null) {
            if (!camelConnectorCatalog.hasConnector(groupId, artifactId, version)) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode tree = mapper.readTree(json[0]);
                    String name = tree.get("name").textValue();
                    String description = tree.get("description").textValue();
                    Iterator<JsonNode> it = tree.withArray("labels").iterator();

                    CollectionStringBuffer csb = new CollectionStringBuffer(",");
                    while (it.hasNext()) {
                        String text = it.next().textValue();
                        csb.append(text);
                    }

                    camelConnectorCatalog.addConnector(groupId, artifactId, version,
                        name, description, csb.toString(), json[0], json[1]);

                    found = true;
                } catch (Throwable e) {
                    LOG.warn("Error parsing Connector JSon due " + e.getMessage(), e);
                }
            }
        }
        return found;
    }

}
