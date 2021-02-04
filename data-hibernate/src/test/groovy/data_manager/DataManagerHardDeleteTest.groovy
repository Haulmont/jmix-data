/*
 * Copyright (c) 2008-2019 Haulmont.
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

package data_manager

import io.jmix.core.DataManager
import io.jmix.core.Id
import io.jmix.core.SaveContext
import io.jmix.data.PersistenceHints
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import test_support.DataSpec
import test_support.entity.sales.Customer

import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class DataManagerHardDeleteTest extends DataSpec {

    @Autowired
    DataManager dm

    @Autowired
    TransactionTemplate tx

    @PersistenceContext
    EntityManager entityManager

    def "hard delete of soft-deleted entity"() {

        def customer = dm.create(Customer)
        def customer1 = dm.save(customer)

        boolean sdBefore = true, sdAfter = true

        when:

        tx.executeWithoutResult { status ->
            sdBefore = PersistenceHints.isSoftDeletion(entityManager)

            dm.save(new SaveContext().removing(customer1).setSoftDeletion(false))

            sdAfter = PersistenceHints.isSoftDeletion(entityManager)
        }

        then:

        !dm.load(Id.of(customer1)).softDeletion(false).optional().isPresent()
        sdBefore
        sdAfter
    }
}
