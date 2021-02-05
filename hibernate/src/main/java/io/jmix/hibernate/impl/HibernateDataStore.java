/*
 * Copyright 2019 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.hibernate.impl;

import com.google.common.collect.Lists;
import io.jmix.core.*;
import io.jmix.core.datastore.AbstractDataStore;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.data.DataProperties;
import io.jmix.data.PersistenceHints;
import io.jmix.data.StoreAwareLocator;
import io.jmix.data.accesscontext.ReadEntityQueryContext;
import io.jmix.data.impl.EntityChangedEventInfo;
import io.jmix.data.impl.EntityEventManager;
import io.jmix.data.impl.JpqlQueryBuilder;
import io.jmix.data.impl.QueryResultsManager;
import io.jmix.data.persistence.DbmsSpecifics;
import org.hibernate.QueryException;
import org.hibernate.engine.spi.SessionImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static io.jmix.core.entity.EntityValues.getValue;

/**
 * INTERNAL.
 * Implementation of the {@link DataStore} interface working with a relational database using JPA.
 */
@Component(HibernateDataStore.NAME)
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class HibernateDataStore extends AbstractDataStore implements DataSortingOptions {

    public static final String NAME = "hibernate_HibernateDataStore";

    public static final String STORE_NAME = Stores.MAIN;

    public static final String LOAD_TX_PREFIX = "HibernateDataStore-load-";
    public static final String SAVE_TX_PREFIX = "HibernateDataStore-save-";

    private static final Logger log = LoggerFactory.getLogger(HibernateDataStore.class);

    @Autowired
    protected DataProperties properties;

    @Autowired
    protected FetchPlans fetchPlans;

    @Autowired
    protected AccessManager accessManager;

    @Autowired
    protected QueryResultsManager queryResultsManager;

    @Autowired
    protected QueryTransformerFactory queryTransformerFactory;

    @Autowired
    protected HibernateEntityChangedEventManager entityChangedEventManager;

    @Autowired
    protected EntityEventManager entityEventManager;

    @Autowired
    protected DbmsSpecifics dbmsSpecifics;

    @Autowired
    protected StoreAwareLocator storeAwareLocator;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected ExtendedEntities extendedEntities;

    @Autowired
    protected ObjectProvider<JpqlQueryBuilder> jpqlQueryBuilderProvider;

    @Autowired
    protected HibernatePersistenceSupport persistenceSupport;

    @Autowired
    protected FetchPlanRepository fetchPlanRepository;

    protected String storeName;

    protected static final AtomicLong txCount = new AtomicLong();

    @Override
    public String getName() {
        return storeName;
    }

    @Override
    public void setName(String name) {
        this.storeName = name;
    }

    @Nullable
    @Override
    protected Object loadOne(LoadContext<?> context) {
        EntityManager em = storeAwareLocator.getEntityManager(storeName);

        em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());

        Query query = createQuery(em, context, false);

        List<Object> resultList = executeQuery(query, isSingleResult(context));

        return resultList.isEmpty() ? null : resultList.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Object> loadAll(LoadContext<?> context) {
        MetaClass metaClass = extendedEntities.getEffectiveMetaClass(context.getEntityMetaClass());

        queryResultsManager.savePreviousQueryResults(context);

        EntityManager em = storeAwareLocator.getEntityManager(storeName);
        em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());

        if (!context.getIds().isEmpty()) {
            if (metadataTools.hasCompositePrimaryKey(metaClass)) {
                return loadAllByIds(context, em);
            } else {
                return loadAllByIdBatches(context, em);
            }
        } else {
            Query query = createQuery(em, context, false);
            return executeQuery(query, false);
        }
    }

    protected List<Object> loadAllByIds(LoadContext<?> context, EntityManager em) {
        LoadContext<?> contextCopy = context.copy();
        contextCopy.setIds(Collections.emptyList());

        List<Object> entities = new ArrayList<>(context.getIds().size());

        for (Object id : context.getIds()) {
            contextCopy.setId(id);
            Query query = createQuery(em, contextCopy, false);
            List<Object> list = executeQuery(query, true);
            entities.addAll(list);
        }

        return entities;
    }

    @SuppressWarnings("unchecked")
    protected List<Object> loadAllByIdBatches(LoadContext<?> context, EntityManager em) {
        Integer batchSize = dbmsSpecifics.getDbmsFeatures(storeName).getMaxIdsBatchSize();

        List<Object> resultList = new ArrayList<>(context.getIds().size());

        List<List<Object>> partitions = Lists.partition((List<Object>) context.getIds(),
                batchSize == null ? Integer.MAX_VALUE : batchSize);
        for (List<Object> partition : partitions) {
            LoadContext<Object> contextCopy = (LoadContext<Object>) context.copy();
            contextCopy.setIds(partition);

            Query query = createQuery(em, contextCopy, false);
            List<Object> list = executeQuery(query, false);

            resultList.addAll(list);
        }

        return resultList;
    }

    @Override
    protected long countAll(LoadContext<?> context) {
        queryResultsManager.savePreviousQueryResults(context);

        EntityManager em = storeAwareLocator.getEntityManager(storeName);
        em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());

        Query query = createQuery(em, context, true);
        Number result = (Number) query.getSingleResult();

        return result.longValue();
    }

    @Override
    protected Set<Object> saveAll(SaveContext context) {
        EntityManager em = storeAwareLocator.getEntityManager(storeName);

        Set<Object> result = new HashSet<>();
        for (Object entity : context.getEntitiesToSave()) {
            if (entityStates.isNew(entity)) {
                entityEventManager.publishEntitySavingEvent(entity, true);
                em.persist(entity);
                result.add(entity);
            }
        }

        for (Object entity : context.getEntitiesToSave()) {
            if (!entityStates.isNew(entity)) {
                entityEventManager.publishEntitySavingEvent(entity, false);
                Object merged = em.merge(entity);
                result.add(merged);
            }
        }

        return result;
    }

    @Override
    protected Set<Object> deleteAll(SaveContext context) {
        EntityManager em = storeAwareLocator.getEntityManager(storeName);
        Set<Object> result = new HashSet<>();
        boolean softDeletionBefore = PersistenceHints.isSoftDeletion(em);
        try {
            em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());
            for (Object entity : context.getEntitiesToRemove()) {
                Object merged = em.merge(entity);
                em.remove(merged);
                result.add(merged);
            }
        } finally {
            em.setProperty(PersistenceHints.SOFT_DELETION, softDeletionBefore);
        }
        return result;
    }

    //    @Override
    protected List<Object> loadAllValues(ValueLoadContext context) {
        EntityManager em = storeAwareLocator.getEntityManager(storeName);
        em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());

        Query query = createLoadQuery(em, context);
        return executeQuery(query, false);
    }

    @Override
    protected Object beginLoadTransaction(boolean joinTransaction) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName(LOAD_TX_PREFIX + txCount.incrementAndGet());

        if (properties.isUseReadOnlyTransactionForLoad()) {
            def.setReadOnly(true);
        }
        if (joinTransaction) {
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        } else {
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        PlatformTransactionManager txManager = storeAwareLocator.getTransactionManager(storeName);

        return txManager.getTransaction(def);
    }

    @Override
    protected void beforeCommitLoadTransaction(LoadContext<?> context, Collection<Object> entities) {
        if (context.isJoinTransaction()) {
            EntityManager em = storeAwareLocator.getEntityManager(storeName);
            for (Object entity : entities) {
                detachEntity(em, entity, context.getFetchPlan(), false);
                entityEventManager.publishEntityLoadingEvent(entity);
            }
        }
    }

    @Override
    protected void rollbackTransaction(Object transaction) {
        TransactionStatus transactionStatus = (TransactionStatus) transaction;
        if (!transactionStatus.isCompleted()) {
            PlatformTransactionManager txManager = storeAwareLocator.getTransactionManager(storeName);
            txManager.rollback(transactionStatus);
        }
    }

    @Override
    protected void commitTransaction(Object transaction) {
        PlatformTransactionManager txManager = storeAwareLocator.getTransactionManager(storeName);
        txManager.commit((TransactionStatus) transaction);
    }

    protected Object beginSaveTransaction(boolean joinTransaction) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName(SAVE_TX_PREFIX + txCount.incrementAndGet());

        if (joinTransaction) {
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        } else {
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        PlatformTransactionManager txManager = storeAwareLocator.getTransactionManager(storeName);
        return txManager.getTransaction(def);
    }

    @Override
    protected void beforeCommitSaveTransaction(SaveContext context, Collection<Object> savedEntities, Collection<Object> removedEntities) {
        if (context.isJoinTransaction()) {
            List<Object> entities = new ArrayList<>(savedEntities);
            entities.addAll(removedEntities);

            EntityManager em = storeAwareLocator.getEntityManager(storeName);

            List<EntityChangedEventInfo> eventsInfo = entityChangedEventManager.collect((SessionImplementor) em, entities);

            boolean softDeletionBefore = PersistenceHints.isSoftDeletion(em);
            try {
                em.setProperty(PersistenceHints.SOFT_DELETION, context.isSoftDeletion());
                persistenceSupport.processFlush(em, false);
                ((EntityManager) em.getDelegate()).flush();
            } finally {
                em.setProperty(PersistenceHints.SOFT_DELETION, softDeletionBefore);
            }

            //noinspection rawtypes
            List<EntityChangedEvent> events = new ArrayList<>(eventsInfo.size());
            for (EntityChangedEventInfo info : eventsInfo) {
                events.add(new EntityChangedEvent<>(info.getSource(),
                        Id.of(info.getEntity()), info.getType(), info.getChanges(), info.getOriginalMetaClass()));
            }

            for (Object entity : entities) {
                detachEntity(em, entity, context.getFetchPlans().get(entity), true);
            }

            entityChangedEventManager.publish(events);
        }
    }

    protected Query createQuery(EntityManager em, LoadContext<?> context, boolean countQuery) {
        MetaClass metaClass = extendedEntities.getEffectiveMetaClass(context.getEntityMetaClass());

        LoadContext.Query contextQuery = context.getQuery();

        JpqlQueryBuilder<JmixHibernateQuery> queryBuilder = jpqlQueryBuilderProvider.getObject();

        queryBuilder.setId(context.getId())
                .setIds(context.getIds())
                .setEntityName(metaClass.getName());

        if (contextQuery != null) {
            queryBuilder.setQueryString(contextQuery.getQueryString())
                    .setCondition(contextQuery.getCondition())
                    .setQueryParameters(contextQuery.getParameters());
            if (!countQuery) {
                queryBuilder.setSort(contextQuery.getSort());
            }
        }

        if (countQuery) {
            queryBuilder.setCountQuery();
        }

        if (!context.getPreviousQueries().isEmpty()) {
            log.debug("Restrict query by previous results");
            //todo MG maybe use user key instead of session id
//            queryBuilder.setPreviousResults(userSessionSource.getUserSession().getId(), context.getQueryKey());
        }

        JmixHibernateQuery<?> query = queryBuilder.getQuery(em);

        if (contextQuery != null) {
            if (contextQuery.getFirstResult() != 0)
                query.setFirstResult(contextQuery.getFirstResult());
            if (contextQuery.getMaxResults() != 0)
                query.setMaxResults(contextQuery.getMaxResults());
            if (contextQuery.isCacheable()) {
                query.setHint(PersistenceHints.CACHEABLE, contextQuery.isCacheable());
            }
        }

        for (Map.Entry<String, Object> hint : context.getHints().entrySet()) {
            query.setHint(hint.getKey(), hint.getValue());
        }

        if (!countQuery) {
            query.setHint(PersistenceHints.FETCH_PLAN, createFetchPlan(context));
        }

        ReadEntityQueryContext queryContext = new ReadEntityQueryContext(query, metaClass, queryTransformerFactory);
        accessManager.applyConstraints(queryContext, context.getAccessConstraints());

        query = (JmixHibernateQuery<?>) queryContext.getResultQuery();

        return query;
    }

    protected Query createLoadQuery(EntityManager em, ValueLoadContext context) {
        JpqlQueryBuilder queryBuilder = jpqlQueryBuilderProvider.getObject();

        ValueLoadContext.Query contextQuery = context.getQuery();

        queryBuilder.setValueProperties(context.getProperties())
                .setQueryString(contextQuery.getQueryString())
                .setCondition(contextQuery.getCondition())
                .setSort(contextQuery.getSort())
                .setQueryParameters(contextQuery.getParameters());

        Query query = queryBuilder.getQuery(em);

        if (contextQuery.getFirstResult() != 0)
            query.setFirstResult(contextQuery.getFirstResult());
        if (contextQuery.getMaxResults() != 0)
            query.setMaxResults(contextQuery.getMaxResults());

        return query;
    }

    protected FetchPlan createFetchPlan(LoadContext<?> context) {
        MetaClass metaClass = extendedEntities.getEffectiveMetaClass(context.getEntityMetaClass());
        FetchPlan fetchPlan = context.getFetchPlan() != null ? context.getFetchPlan() :
                fetchPlanRepository.getFetchPlan(metaClass, FetchPlan.BASE);

        return fetchPlans.builder(fetchPlan)
                .partial(context.isLoadPartialEntities())
                .build();
    }

    protected List<Object> executeQuery(Query query, boolean singleResult) {
        List<Object> list;
        try {
            if (singleResult) {
                try {
                    Object result = query.getSingleResult();
                    list = new ArrayList<>(1);
                    list.add(result);
                } catch (NoResultException e) {
                    list = Collections.emptyList();
                }
            } else {
                //noinspection unchecked
                list = query.getResultList();
            }
        } catch (PersistenceException e) {
            if (e.getCause() instanceof QueryException
                    && e.getMessage() != null
                    && e.getMessage().contains("Fetch group cannot be set on report query")) {
                throw new DevelopmentException("DataManager cannot execute query for single attributes");
            } else {
                throw e;
            }
        }
        return list;
    }


    protected <E> void detachEntity(EntityManager em, @Nullable E rootEntity, @Nullable FetchPlan fetchPlan, boolean loadedOnly) {
        if (rootEntity == null)
            return;

        em.detach(rootEntity);

        if (fetchPlan == null)
            return;

        metadataTools.traverseAttributesByFetchPlan(fetchPlan, rootEntity, loadedOnly, new EntityAttributeVisitor() {
            @Override
            public void visit(Object entity, MetaProperty property) {

                Object value = getValue(entity, property.getName());
                if (value != null) {
                    if (property.getRange().getCardinality().isMany()) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> collection = (Collection<Object>) value;
                        for (Object element : collection) {
                            em.detach(element);
                        }
                    } else {
                        em.detach(value);
                    }
                }
            }

            @Override
            public boolean skip(MetaProperty property) {
                return !property.getRange().isClass()
                        || metadataTools.isEmbedded(property)
                        || metadataTools.isEmbeddedId(property)
                        || !metadataTools.isPersistent(property);
            }
        });
    }

    /**
     * @param context - loading context
     * @return false if maxResults=1 and the query is not by ID we should not use getSingleResult() for backward compatibility
     */
    protected boolean isSingleResult(LoadContext<?> context) {
        return !(context.getQuery() != null && context.getQuery().getMaxResults() == 1)
                && context.getId() != null;
    }

    @Override
    public boolean isNullsLastSorting() {
        return dbmsSpecifics.getDbmsFeatures(storeName).isNullsLastSorting();
    }

    @Override
    public boolean supportsLobSortingAndFiltering() {
        return dbmsSpecifics.getDbmsFeatures(storeName).supportsLobSortingAndFiltering();
    }
}
