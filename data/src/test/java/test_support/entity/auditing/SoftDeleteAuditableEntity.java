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

package test_support.entity.auditing;

import io.jmix.core.annotation.DeletedBy;
import io.jmix.core.annotation.DeletedDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.util.Date;

@Table(name = "TEST_SOFTDELETE_AUDITABLE_ENTITY")
@Entity(name = "test_SoftDeleteAuditableEntity")
public class SoftDeleteAuditableEntity extends AuditableSubclass {
    private static final long serialVersionUID = -3766659443394014860L;

    @DeletedBy
    @Column(name = "WHO_DELETED", length = 50)
    protected String whoDeleted;

    @DeletedDate
    @Column(name = "WHEN_DELETED")
    protected Date whenDeleted;

    @Column(name = "REASON")
    protected String reason;

    public String getWhoDeleted() {
        return whoDeleted;
    }

    public void setWhoDeleted(String whoDeleted) {
        this.whoDeleted = whoDeleted;
    }

    public Date getWhenDeleted() {
        return whenDeleted;
    }

    public void setWhenDeleted(Date whenDeleted) {
        this.whenDeleted = whenDeleted;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}