/*
 * Copyright 2020 Haulmont.
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

package io.jmix.data.impl.lazyloading;

import io.jmix.core.LoadContext;
import io.jmix.core.UuidProvider;
import io.jmix.core.impl.SerializationContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.beans.factory.BeanFactory;

import java.io.IOException;
import java.util.UUID;

import static io.jmix.core.entity.EntitySystemAccess.getSecurityState;

public class SingleValueOwningPropertyHolder extends AbstractSingleValueHolder {
    private static final long serialVersionUID = 8740384435315015951L;

    protected Object owner;
    protected String propertyName;
    protected Class<?> propertyClass;
    protected Object entityId;

    public SingleValueOwningPropertyHolder(BeanFactory beanFactory,
                                           Object owner,
                                           Class<?> propertyClass,
                                           String propertyName,
                                           Object entityId) {
        super(beanFactory, owner);
        this.owner = owner;
        this.propertyName = propertyName;
        this.propertyClass = propertyClass;
        this.entityId = entityId;
    }

    public Object getEntityId() {
        return convertId(entityId, getMetadata().getClass(propertyClass));
    }

    protected Object loadValue() {
        MetaClass metaClass = getMetadata().getClass(propertyClass);
        LoadOptions loadOptions = getLoadOptions();

        LoadContext<?> loadContext = new LoadContext<>(metaClass)
                .setId(getEntityId())
                .setSoftDeletion(false)
                .setHints(loadOptions.getHints())
                .setAccessConstraints(loadOptions.getAccessConstraints());

        Object value = getDataManager().load(loadContext);

        if (value == null) {
            getSecurityState(owner).addErasedId(propertyName, getEntityId());
        }

        return value;
    }

    protected Object convertId(Object entityId, MetaClass metaClass) {
        MetaProperty primaryKeyProperty = getMetadataTools().getPrimaryKeyProperty(metaClass);
        if (primaryKeyProperty != null && UUID.class.equals(primaryKeyProperty.getJavaType())) {
            return entityId instanceof String ? UuidProvider.fromString((String) entityId) : entityId;
        }
        return entityId;
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        beanFactory = SerializationContext.getThreadLocalBeanFactory();
    }
}