/* Copyright (C) 2010 SpringSource
 *
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
 */
package org.grails.datastore.gorm.neo4j;

import org.grails.datastore.mapping.config.Property;
import org.grails.datastore.mapping.config.utils.PropertyResolverMap;
import org.grails.datastore.mapping.core.AbstractDatastore;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.core.StatelessDatastore;
import org.grails.datastore.mapping.graph.GraphDatastore;
import org.grails.datastore.mapping.model.DatastoreConfigurationException;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Simple;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertyResolver;

import java.util.*;

/**
 * Datastore implementation for Neo4j backend
 *
 * @author Stefan Armbruster (stefan@armbruster-it.de)
 * @author Graeme Rocher
 *
 * @since 1.0
 */
public class Neo4jDatastore extends AbstractDatastore implements InitializingBean, DisposableBean, StatelessDatastore, GraphDatastore {

    public static final String DEFAULT_LOCATION = "data/neo4j";
    public static final String SETTING_NEO4J_URL = "grails.neo4j.url";
    public static final String SETTING_NEO4J_LOCATION = "grails.neo4j.location";
    public static final String SETTING_NEO4J_USERNAME = "grails.neo4j.username";
    public static final String SETTING_NEO4J_PASSWORD = "grails.neo4j.password";
    public static final String SETTING_NEO4J_HIGH_AVAILABILITY = "grails.neo4j.ha";
    public static final String SETTING_NEO4J_DB_PROPERTIES = "grails.neo4j.dbProperties";

    private static Logger log = LoggerFactory.getLogger(Neo4jDatastore.class);

    protected GraphDatabaseService graphDatabaseService;
    protected boolean skipIndexSetup = false;
    protected IdGenerator idGenerator = new SnowflakeIdGenerator();


    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param mappingContext The {@link MappingContext} which contains information about the mapped classes
     * @param configuration The configuration for the datastore
     * @param applicationContext The Spring ApplicationContext
     */
    public Neo4jDatastore(MappingContext mappingContext, PropertyResolver configuration, ConfigurableApplicationContext applicationContext) {
        super(mappingContext, new PropertyResolverMap(configuration), applicationContext);
        this.graphDatabaseService = createGraphDatabaseService(configuration);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param mappingContext The {@link MappingContext} which contains information about the mapped classes
     * @param configuration The configuration for the datastore
     * @param applicationContext The Spring ApplicationContext
     */
    public Neo4jDatastore(MappingContext mappingContext, PropertyResolver configuration, ConfigurableApplicationContext applicationContext, GraphDatabaseService graphDatabaseService) {
        super(mappingContext, new PropertyResolverMap(configuration), applicationContext);
        this.graphDatabaseService = graphDatabaseService;
    }

    /**
     * @see {@link #Neo4jDatastore(MappingContext, PropertyResolver, ConfigurableApplicationContext)}
     */
    public Neo4jDatastore(MappingContext mappingContext, ConfigurableApplicationContext applicationContext) {
        this(mappingContext, applicationContext.getEnvironment(), applicationContext);
    }

    /**
     * @see {@link #Neo4jDatastore(MappingContext, PropertyResolver, ConfigurableApplicationContext)}
     */
    public Neo4jDatastore(MappingContext mappingContext, ConfigurableApplicationContext applicationContext, GraphDatabaseService graphDatabaseService) {
        this(mappingContext, applicationContext.getEnvironment(), applicationContext, graphDatabaseService);
    }


    protected GraphDatabaseService createGraphDatabaseService(PropertyResolver configuration) {
        // TODO: Implement configuration properly
        final boolean highAvailability = configuration.getProperty(SETTING_NEO4J_HIGH_AVAILABILITY, Boolean.class, false);
        GraphDatabaseFactory graphDatabaseFactory;
        if(highAvailability) {
            try {
                graphDatabaseFactory = (GraphDatabaseFactory) Class.forName("org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory",true, Thread.currentThread().getContextClassLoader()).newInstance();
            } catch (Throwable e) {
                throw new DatastoreConfigurationException("Cannot load class [org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory] for HA mode. Check neo4j-ha dependency is on your classpath", e);
            }
        }
        else {
            graphDatabaseFactory = new GraphDatabaseFactory();
        }

        final GraphDatabaseBuilder graphDatabaseBuilder = graphDatabaseFactory.newEmbeddedDatabaseBuilder(
                configuration.getProperty(SETTING_NEO4J_LOCATION, DEFAULT_LOCATION)
        );
        final Map dbProperties = configuration.getProperty(SETTING_NEO4J_DB_PROPERTIES, Map.class, new LinkedHashMap<String, Object>());
        if(!dbProperties.containsKey(GraphDatabaseSettings.cache_type.name())) {
            graphDatabaseBuilder.setConfig(GraphDatabaseSettings.cache_type, "soft");
        }

        graphDatabaseBuilder
                .setConfig(dbProperties);


        return graphDatabaseBuilder
                    .newGraphDatabase();
    }

    public void setSkipIndexSetup(boolean skipIndexSetup) {
        this.skipIndexSetup = skipIndexSetup;
    }

    @Override
    protected Session createSession(Map<String, String> connectionDetails) {
        return new Neo4jSession(this, mappingContext, getApplicationContext(), false, graphDatabaseService);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!skipIndexSetup) {
            setupIndexing();
        }
    }

    public long nextIdForType(PersistentEntity pe) {
        return idGenerator.nextId();
    }

    public void setupIndexing() {
        Set<String> schemaStrings = new HashSet<String>(); // using set to avoid duplicate index creation

        for (PersistentEntity persistentEntity:  mappingContext.getPersistentEntities()) {

            for (String label: ((GraphPersistentEntity)persistentEntity).getLabels()) {
                StringBuilder sb = new StringBuilder();
                sb.append("CREATE INDEX ON :").append(label).append("(__id__)");
                schemaStrings.add(sb.toString());
                for (PersistentProperty persistentProperty : persistentEntity.getPersistentProperties()) {
                    Property mappedForm = persistentProperty.getMapping().getMappedForm();
                    if ((persistentProperty instanceof Simple) && (mappedForm != null) && (mappedForm.isIndex())) {
                        sb = new StringBuilder();
                        sb.append("CREATE INDEX ON :").append(label).append("(").append(persistentProperty.getName()).append(")");
                        schemaStrings.add(sb.toString());
                        if(log.isDebugEnabled()) {
                            log.debug("setting up indexing for " + label + " property " + persistentProperty.getName());
                        }
                    }
                }
            }
        }

        final Transaction transaction = graphDatabaseService.beginTx();
        try {
            for (String cypher: schemaStrings) {
                graphDatabaseService.execute(cypher);
                transaction.success();
            }
        } catch (QueryExecutionException e) {
            transaction.failure();
        }
        finally {
            transaction.close();
        }
        if(log.isDebugEnabled()) {
            log.debug("done setting up indexes");
        }
    }

    /**
     * @return The {@link GraphDatabaseService} used by this datastore
     */
    public GraphDatabaseService getGraphDatabaseService() {
        return graphDatabaseService;
    }

    @Override
    public void destroy() throws Exception {
        super.destroy();
        if(graphDatabaseService != null) {
            graphDatabaseService.shutdown();
        }
    }
}