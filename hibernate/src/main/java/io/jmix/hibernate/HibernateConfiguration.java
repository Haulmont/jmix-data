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

package io.jmix.hibernate;

import io.jmix.core.CoreConfiguration;
import io.jmix.core.PersistentAttributesLoadChecker;
import io.jmix.core.annotation.JmixModule;
import io.jmix.data.DataConfiguration;
import io.jmix.hibernate.impl.HibernateDataPersistentAttributesLoadChecker;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@ComponentScan
@ConfigurationPropertiesScan
@JmixModule(dependsOn = {CoreConfiguration.class, DataConfiguration.class})
@PropertySource(name = "io.jmix.hibernate", value = "classpath:/io/jmix/hibernate/module.properties")
@EnableTransactionManagement
public class HibernateConfiguration {

    @Bean("hibernate_HibernateJmixJpaDialect")
    public HibernateJpaDialect jpaDialect() {
        return new HibernateJpaDialect();
    }

    @Bean("hibernate_PersistentAttributesLoadChecker")
    @Primary
    protected PersistentAttributesLoadChecker persistentAttributesLoadChecker(ApplicationContext applicationContext) {
        return new HibernateDataPersistentAttributesLoadChecker(applicationContext);
    }
}