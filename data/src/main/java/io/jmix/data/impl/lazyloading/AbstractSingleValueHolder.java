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

import io.jmix.core.EntityAttributeVisitor;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.beans.factory.BeanFactory;

public abstract class AbstractSingleValueHolder extends AbstractValueHolder {
    private static final long serialVersionUID = -6300542559295657659L;

    public AbstractSingleValueHolder(BeanFactory beanFactory, Object owner) {
        super(beanFactory, owner);
    }

    @Override
    protected void afterLoadValue(Object value) {
        if (value != null) {
            getMetadataTools().traverseAttributes(value, new SingleValuePropertyVisitor());
        }
    }

    protected class SingleValuePropertyVisitor implements EntityAttributeVisitor {
        @Override
        public void visit(Object entity, MetaProperty property) {
            if (property.getRange().asClass().getJavaClass().isAssignableFrom(getOwner().getClass())) {
                replaceToExistingReferences(entity, property, getOwner());
            }
            if (getLoadOptions().isSoftDeletion()) {
                replaceLoadOptions(entity, property);
            }
        }

        @Override
        public boolean skip(MetaProperty property) {
            return !property.getRange().isClass();
        }
    }
}