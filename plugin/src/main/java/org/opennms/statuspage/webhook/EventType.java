/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.statuspage.webhook;

import java.util.Arrays;

public enum EventType {
    TRIGGER("incident.trigger"),
    ACKNOWLEDGE("incident.acknowledge"),
    UNACKNOWLEDGE("incident.unacknowledge"),
    RESOLVE("incident.resolve"),
    ASSIGN("incident.assign"),
    ESCALATE("incident.escalate"),
    DELEGATE("incident.delegate"),
    ANNOTATE("incident.annotate");

    String label;

    EventType(String label) {
        this.label = label;
    }

    String getLabel() {
        return label;
    }

    static EventType fromLabel(String label) {
        return Arrays.stream(EventType.values())
                .filter(e -> e.getLabel().equalsIgnoreCase(label.trim()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown event type: " + label));
    }
}
